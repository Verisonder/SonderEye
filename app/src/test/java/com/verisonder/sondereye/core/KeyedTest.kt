package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedTest {
    @Test fun aisPosition() {
        val text = """{"MessageType":"PositionReport","MetaData":{"MMSI":242123456,"ShipName":"TANGER MED 1   ","latitude":35.9,"longitude":-5.5,"time_utc":"2026-09-29 12:00:00"},
          "Message":{"PositionReport":{"UserID":242123456,"Latitude":35.901,"Longitude":-5.502,"Sog":12.4,"Cog":88.0,"TrueHeading":511}}}"""
        val m = Ais.parse(text, 1000L) as Ais.Msg.Position
        assertEquals(242123456L, m.ship.mmsi)
        assertEquals("TANGER MED 1", m.ship.name)
        assertEquals(35.901, m.ship.lat, 0.0)
        assertNull(m.ship.heading) // 511 = unknown
        assertEquals(88.0, m.ship.bearing!!, 0.0) // falls back to course
    }

    @Test fun aisOtherShapes() {
        assertTrue(Ais.parse("""{"error":"Api Key Is Not Valid"}""", 0) is Ais.Msg.Error)
        val stat = Ais.parse("""{"MessageType":"ShipStaticData","MetaData":{"MMSI":1},"Message":{"ShipStaticData":{"UserID":1,"Name":"ALBORAN  "}}}""", 0)
        assertEquals("ALBORAN", (stat as Ais.Msg.Name).name)
        val noPos = Ais.parse("""{"MessageType":"PositionReport","MetaData":{"MMSI":2},"Message":{"PositionReport":{"UserID":2,"Latitude":91,"Longitude":181}}}""", 0)
        assertTrue(noPos is Ais.Msg.Other)
        val sub = Ais.subscription("k\"ey", 35.0, -6.0, 36.0, -5.0)
        assertEquals("k\"ey", (Json.parse(sub) as Map<*, *>)["APIKey"])
        assertEquals(listOf(listOf(listOf(35.0, -6.0), listOf(36.0, -5.0))), (Json.parse(sub) as Map<*, *>)["BoundingBoxes"])
    }

    @Test fun windy() {
        val text = """{"total":2,"webcams":[
          {"webcamId":1234567890,"title":"Tangier: Port","status":"active","location":{"city":"Tangier","country":"Morocco","latitude":35.78,"longitude":-5.81},
           "images":{"current":{"icon":"i","thumbnail":"t","preview":"https://imgproxy.windy.com/p.jpg"}},"urls":{"detail":"https://www.windy.com/webcams/1234567890"}},
          {"webcamId":2,"title":"Off","status":"inactive","location":{"latitude":1,"longitude":2}}]}"""
        val w = Windy.parse(text)
        assertEquals(1, w.size)
        assertEquals("Tangier, Morocco", w[0].place)
        assertEquals("https://imgproxy.windy.com/p.jpg", w[0].preview)
        assertTrue(Windy.url(35.0, -5.0, 900).contains("nearby=35.0000,-5.0000,250"))
    }

    @Test fun worldViews() {
        assertTrue(Firms.worldUrl(" k ").endsWith("/api/area/csv/k/VIIRS_NOAA20_NRT/world/1"))
        val u = Windy.topUrl(100)
        assertTrue(u, "sortKey=popularity" in u && "sortDirection=desc" in u && "offset=100" in u && "include=images,location,urls" in u)
        val h = listOf(1.0, null, 30.0, 5.0).mapIndexed { i, f -> Hotspot(i.toDouble(), 0.0, f, null, "", true) }
        assertEquals(listOf(30.0, 5.0), Firms.strongest(h, 2).map { it.frpMw })
    }

    @Test fun firms() {
        val csv = """latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight
35.1,-5.3,330.2,0.4,0.5,2026-09-29,142,N20,VIIRS,n,2.0NRT,290.1,5.6,D
36.2,-4.9,310.0,0.4,0.4,2026-09-29,1318,N20,VIIRS,h,2.0NRT,285.0,,N
"""
        val f = Firms.parse(csv)
        assertEquals(2, f.size)
        assertEquals("2026-09-29 0142", f[0].acquired)
        assertEquals(5.6, f[0].frpMw!!, 0.0)
        assertNull(f[1].frpMw)
        assertEquals(false, f[1].day)
        assertEquals(0, Firms.parse("latitude,longitude\n").size)
        try { Firms.parse("Invalid MAP_KEY."); throw AssertionError("accepted") } catch (e: Json.ParseError) { }
        assertTrue(Firms.url(" KEY ", -6.0, 35.0, -5.0, 36.0).endsWith("/KEY/VIIRS_NOAA20_NRT/-6.000,35.000,-5.000,36.000/1"))
    }
}
