package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class LiveTest {

    private val iss = """
ISS (ZARYA)
1 25544U 98067A   26270.51782528  .00016717  00000-0  30271-3 0  9993
2 25544  51.6412 208.9163 0006317 169.8391 190.2932 15.49815301471802
"""

    @Test fun adsbParses() {
        val text = """{"ac":[
          {"hex":"3C6DD4","type":"adsb_icao","flight":"DLH4AB  ","r":"D-AIZZ","t":"A320","alt_baro":36000,"gs":451.2,"track":212.5,"lat":35.9,"lon":-5.1},
          {"hex":"020123","flight":"RAM101 ","alt_baro":"ground","gs":3.1,"lat":35.73,"lon":-5.91},
          {"hex":"abc123","alt_baro":12000},
          {"hex":"","lat":1.0,"lon":2.0}
        ],"msg":"No error","now":1790625422353,"total":4}"""
        val r = Adsb.parse(text)
        assertEquals(2, r.flights.size)
        assertEquals(2, r.skipped)
        val a = r.flights[0]
        assertEquals("3c6dd4", a.hex)
        assertEquals("DLH4AB", a.callsign)
        assertEquals("A320", a.type)
        assertEquals(36000, a.altFt)
        assertFalse(a.onGround)
        assertEquals(212.5, a.track!!, 0.0)
        val g = r.flights[1]
        assertTrue(g.onGround)
        assertNull(g.altFt)
        assertNull(g.track)
        assertEquals("https://api.adsb.lol/v2/lat/35.7595/lon/-5.8340/dist/250", Adsb.url(35.7595, -5.834, 900))
    }

    @Test fun adsbRejectsNonFeeds() {
        for (bad in listOf("[]", "{}", "<html>429</html>")) {
            try { Adsb.parse(bad); fail("Accepted: $bad") } catch (e: Json.ParseError) { }
        }
    }

    @Test fun eonetParses() {
        val text = """{"title":"EONET Events","events":[
          {"id":"EONET_1","title":"Fire near Chefchaouen","categories":[{"id":"wildfires","title":"Wildfires"}],
           "sources":[{"id":"InciWeb","url":"https://example.org/fire"}],
           "geometry":[{"date":"2026-09-26T00:00:00Z","type":"Point","coordinates":[-5.2,35.1]},
                       {"date":"2026-09-27T12:30:00Z","type":"Point","coordinates":[-5.3,35.2]}]},
          {"id":"EONET_2","title":"Iceberg A23","categories":[{"id":"seaLakeIce","title":"Sea and Lake Ice"}],"sources":[],
           "geometry":[{"date":"2026-09-20T00:00:00Z","type":"Polygon","coordinates":[[[10.0,-60.0],[12.0,-60.0],[12.0,-62.0],[10.0,-62.0]]]}]},
          {"id":"EONET_3","title":"Broken","categories":[],"geometry":[]}
        ]}"""
        val r = Eonet.parse(text)
        assertEquals(2, r.events.size)
        assertEquals(1, r.skipped)
        val f = r.events[0]
        assertEquals("wildfires", f.category)
        assertEquals(35.2, f.lat, 0.0) // latest geometry wins
        assertEquals(-5.3, f.lon, 0.0)
        assertEquals("https://example.org/fire", f.url)
        assertEquals(Eonet.isoMs("2026-09-27T12:30:00Z"), f.timeMs)
        val ice = r.events[1]
        assertEquals(-61.0, ice.lat, 1e-9)
        assertEquals(11.0, ice.lon, 1e-9)
        assertNull(ice.url)
    }

    @Test fun isoTimes() {
        assertEquals(0L, Eonet.isoMs("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, Eonet.isoMs("2000-02-29T00:00:00Z")) // leap day
        assertEquals(1_790_553_600_000L + 45_296_000L, Eonet.isoMs("2026-09-28T12:34:56Z"))
        assertNull(Eonet.isoMs("yesterday"))
    }

    @Test fun tleParsesAndSkipsDeepSpace() {
        val gps = """
GPS BIIR-2  (PRN 13)
1 24876U 97035A   26270.50000000  .00000000  00000-0  00000-0 0  9990
2 24876  55.7000 100.0000 0100000  50.0000 310.0000  2.00560000100000
"""
        val r = Tle.parseAll(iss + gps + "\ngarbage line\n")
        assertEquals(2, r.tles.size) // deep-space orbits are kept, with the simplified model
        assertEquals(1, r.deepSpace)
        val t = r.tles[0]
        assertEquals("ISS (ZARYA)", t.name)
        assertEquals(25544, t.norad)
        assertEquals(0.30271e-3, t.bstar, 1e-12)
        assertEquals(51.6412, t.inclDeg, 0.0)
        assertEquals(0.0006317, t.ecc, 1e-12)
        assertEquals(92.9, t.periodMin, 0.1)
        // 2026 day 270.5178 = 27 Sep 2026 12:25:40 UTC
        assertEquals(Eonet.isoMs("2026-09-27T12:25:40Z")!!.toDouble(), t.epochMs.toDouble(), 1000.0)
    }

    @Test fun deepSpaceWithinTensOfKilometresOfSdp4() {
        // Reference positions from python-sgp4 (SDP4), TEME km, 3 days after epoch.
        val gps = Tle.parse("GPS", "1 55268U 23009A   26268.51213301 -.00000048  00000-0  00000-0 0  9997",
            "2 55268  55.0632 225.4718 0010373 223.4096 136.5288  2.00563164 26401")
        val geo = Tle.parse("GEO", "1 41748U 16053B   26268.83617014 -.00000278  00000-0  00000-0 0  9991",
            "2 41748   0.0147  88.4130 0001722 128.6006 213.5781  1.00271283 36551")
        for (t in listOf(gps, geo)) {
            val s = Sgp4(t)
            assertTrue(s.deepSpace)
            val p = s.propagate(4320.0)!!
            val r = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            assertTrue("radius $r", r in 26_000.0..43_000.0)
        }
    }

    @Test fun issIsWhereAnIssShouldBe() {
        val t = Tle.parseAll(iss).tles[0]
        val s = Sgp4(t)
        for (h in 0..24) {
            val ms = t.epochMs + h * 3_600_000L
            val p = s.ecefAt(ms)!!
            val alt = (p.len() - EARTH_R) / 1000
            val lat = Geo.latLon(p)[0]
            assertTrue("alt $alt", alt in 380.0..450.0)
            assertTrue("lat $lat", abs(lat) <= 51.7)
            assertEquals(7.66, s.speedAt(ms)!!, 0.05)
        }
    }

    @Test fun lookAnglesStraightUpAndCompass() {
        val above = Geo.ecef(35.77, -5.8, 400_000.0)
        assertEquals(90.0, Sky.lookAngles(35.77, -5.8, above)[0], 1e-6)
        val north = Geo.ecef(40.0, -5.8, 400_000.0)
        assertEquals(0.0, Sky.lookAngles(35.77, -5.8, north)[1], 0.5)
        val east = Geo.ecef(35.77, -1.0, 400_000.0)
        assertEquals(90.0, Sky.lookAngles(35.77, -5.8, east)[1], 3.0)
        assertEquals("N", Sky.compass(355.0))
        assertEquals("SW", Sky.compass(225.0))
    }

    @Test fun issPassesOverTangier() {
        val t = Tle.parseAll(iss).tles[0]
        val passes = Sky.passes(Sgp4(t), 35.77, -5.8, t.epochMs, hours = 48.0)
        assertTrue("passes ${passes.size}", passes.size >= 2)
        for (p in passes) {
            val min = (p.endMs - p.startMs) / 60_000.0
            assertTrue("duration $min", min in 0.5..12.0)
            assertTrue(p.maxElevationDeg >= 10.0 && p.maxElevationDeg <= 90.0)
            // The edges really are at 10°.
            val s = Sgp4(t)
            assertEquals(10.0, Sky.lookAngles(35.77, -5.8, s.ecefAt(p.startMs)!!)[0], 0.3)
        }
        assertTrue(passes.zipWithNext().all { (a, b) -> b.startMs > a.endMs })
    }
}
