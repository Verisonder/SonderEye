package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitTest {

    @Test fun busLinesFromOverpass() {
        val text = """
            {"elements":[
              {"type":"relation","id":6304295,"tags":{"type":"route","route":"bus","ref":"L1","from":"Beni Makada","to":"Boukhalef","network":"ALSA TANGER","colour":"#E30613"},
               "members":[
                 {"type":"way","ref":1,"role":"","geometry":[{"lat":35.76,"lon":-5.80},{"lat":35.761,"lon":-5.801},null,{"lat":35.77,"lon":-5.81},{"lat":35.771,"lon":-5.811}]},
                 {"type":"node","ref":10,"role":"stop","lat":35.76,"lon":-5.80},
                 {"type":"node","ref":11,"role":"platform","lat":35.7601,"lon":-5.8001},
                 {"type":"node","ref":12,"role":"platform_entry_only","lat":35.77,"lon":-5.81}
               ]},
              {"type":"relation","id":7,"tags":{"type":"route","route":"bus","name":"Ligne 7"},
               "members":[{"type":"node","ref":11,"role":"platform"}]},
              {"type":"node","id":11,"lat":35.7601,"lon":-5.8001,"tags":{"name":"Place de France"}},
              {"type":"node","id":12,"lat":35.77,"lon":-5.81},
              {"type":"node","id":10,"lat":35.76,"lon":-5.80}
            ]}
        """
        val net = BusLines.parse(text)
        assertEquals(2, net.lines.size)
        val l1 = net.lines.first { it.id == 6304295L }
        assertEquals("L1", l1.short)
        assertEquals("Beni Makada → Boukhalef", l1.route)
        assertEquals(0xE30613, l1.colour)
        assertEquals(2, l1.paths.size) // the null broke the way in two
        assertEquals(listOf(11L, 12L), l1.stopIds) // platforms, not stop positions
        assertEquals("Ligne 7", net.lines.first { it.id == 7L }.short)
        val pf = net.stops.first { it.id == 11L }
        assertEquals("Place de France", pf.name)
        assertEquals(listOf(6304295L, 7L), pf.lineIds)
        assertTrue(net.stops.none { it.id == 10L })
        assertEquals(0xFF0000, BusLines.colour("#f00"))
        assertNull(BusLines.colour("red"))
    }

    @Test fun transitlandFeedsAndJsonVehicles() {
        val ops = """{"operators":[{"name":"ISAL","feeds":[{"onestop_id":"f-isal~rt","spec":"GTFS_RT"},{"onestop_id":"f-isal","spec":"GTFS"}]}]}"""
        val feeds = Transitland.parseRtFeeds(ops)
        assertEquals(1, feeds.size)
        assertEquals("f-isal~rt", feeds[0].onestopId)
        val src = Transitland.parseSource("""{"feeds":[{"urls":{"realtime_vehicle_positions":"https://x/vp.pb"},"authorization":{"type":""}}]}""")
        assertEquals("https://x/vp.pb", src.vehiclesUrl)
        assertTrue(!src.needsKey)
        val camel = """{"entity":[{"id":"1","vehicle":{"trip":{"routeId":"L7"},"position":{"latitude":35.7,"longitude":-5.8,"bearing":90},"timestamp":"1790000000","vehicle":{"id":"v1","label":"42"}}}]}"""
        val a = Transitland.parseVehicles(camel, feeds[0]).single()
        assertEquals("L7", a.routeId); assertEquals("42", a.label); assertEquals(1790000000000L, a.atMs)
        assertEquals("f-isal~rt/v1", a.key)
        val snake = """{"entity":[{"vehicle":{"trip":{"route_id":"3"},"position":{"latitude":1.0,"longitude":2.0},"timestamp":1790000000}}]}"""
        assertEquals("3", Transitland.parseVehicles(snake, feeds[0]).single().routeId)
    }

    @Test fun gtfsRealtimeProtobuf() {
        // Written by the official gtfs-realtime-bindings: a vehicle, an alert, a bare vehicle.
        val hex = "0a0b0a03322e301880f7c4d50612400a026531223a0a080a0274392a024c3712140db7110f4215f697b9c01d000087432d0000084118032880f7c4d5063a025331420c0a066275732d34321202343212100a05616c6572742a0752050a030a017812120a026533220c120a0d00004e42158fc2f5bd"
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val buses = GtfsRt.parseVehicles(bytes, RtFeed("f-x~rt", "Test"))
        assertEquals(2, buses.size)
        val b = buses[0]
        assertEquals(35.7673, b.lat, 1e-4); assertEquals(-5.7998, b.lon, 1e-4)
        assertEquals(270.0, b.bearing!!, 1e-6); assertEquals(8.5, b.speedMs!!, 1e-6)
        assertEquals("L7", b.routeId); assertEquals("42", b.label); assertEquals("f-x~rt/bus-42", b.key)
        assertEquals(1790000000000L, b.atMs)
        assertEquals("f-x~rt/e3", buses[1].key)
        assertNull(buses[1].routeId)
    }
}
