package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SkyTest {

    private fun ms(iso: String) = Eonet.isoMs(iso)!!

    @Test fun sunAtSolsticeAndEquinox() {
        // Subsolar latitude is the Sun's declination.
        val june = Geo.latLon(Astro.sun(ms("2026-06-21T08:24:00Z")))
        assertEquals(23.44, june[0], 0.05)
        val march = Geo.latLon(Astro.sun(ms("2026-03-20T14:46:00Z")))
        assertEquals(0.0, march[0], 0.05)
        // Near 12:00 UTC the Sun is roughly over Greenwich (equation of time < 17 min = 4.3°).
        val noon = Geo.latLon(Astro.sun(ms("2026-09-28T12:00:00Z")))
        assertTrue("lon ${noon[1]}", abs(noon[1]) < 4.5)
        val d = Astro.sun(ms("2026-09-28T12:00:00Z")).len()
        assertEquals(1.0, d / 1.495978707e11, 0.02)
    }

    @Test fun sunMovesWestThroughTheMorning() {
        // 29 Sep 2026: subsolar point at 06:41 and 08:00 UTC (reference: 77.3°E and 57.6°E, -2.4°).
        val a = Geo.latLon(Astro.sun(ms("2026-09-29T06:41:00Z")))
        assertEquals(-2.44, a[0], 0.1)
        assertEquals(77.3, a[1], 0.3)
        val b = Geo.latLon(Astro.sun(ms("2026-09-29T08:00:00Z")))
        assertEquals(57.6, b[1], 0.3)
        // Morocco is on GMT since 20 Sep 2026: 06:41 there is after sunrise (about 06:16).
        assertTrue(Astro.sunElevation(35.77, -5.8, ms("2026-09-29T06:41:00Z")) > 2.0)
        assertTrue(Astro.sunElevation(35.77, -5.8, ms("2026-09-29T05:41:00Z")) < -5.0)
    }

    @Test fun dayAndNightInTangier() {
        assertTrue(Astro.sunElevation(35.77, -5.8, ms("2026-09-28T13:00:00Z")) > 40.0)
        assertTrue(Astro.sunElevation(35.77, -5.8, ms("2026-09-28T23:00:00Z")) < -30.0)
    }

    @Test fun moonIsAtMoonDistance() {
        for (day in 1..28) {
            val t = ms("2026-09-%02dT00:00:00Z".format(day))
            val km = Astro.moon(t).len() / 1000
            assertTrue("moon $km km", km in 355_000.0..407_000.0)
            val lat = Geo.latLon(Astro.moon(t))[0]
            assertTrue("moon lat $lat", abs(lat) < 29.0)
        }
        val f = Astro.moonIllumination(ms("2026-09-28T00:00:00Z"))
        assertTrue(f in 0.0..1.0)
    }

    @Test fun shadow() {
        val t = ms("2026-09-28T12:00:00Z")
        val sunDir = Astro.sun(t).norm()
        val dayside = sunDir * (EARTH_R + 400_000.0)
        val nightside = sunDir * -(EARTH_R + 400_000.0)
        assertTrue(Astro.sunlit(dayside, t))
        assertFalse(Astro.sunlit(nightside, t))
        // High above the night side but off to the edge of the shadow: lit.
        val perp = (sunDir cross V3.Z).norm() * (EARTH_R + 800_000.0) + sunDir * -1_000_000.0
        assertTrue(Astro.sunlit(perp, t))
    }

    @Test fun tileSources() {
        val k = TileKey(12, 1990, 1620)
        assertTrue(TileSource.SATELLITE.url(k).endsWith("/World_Imagery/MapServer/tile/12/1620/1990?blankTile=false"))
        assertTrue(TileSource.SATELLITE.url(k).startsWith("https://server.") || TileSource.SATELLITE.url(k).startsWith("https://services."))
        assertEquals(TileKey(9, 248, 202), TileSource.TODAY.keyFor(k))
        assertEquals(k, TileSource.SATELLITE.keyFor(k))
        assertTrue(TileSource.TODAY.url(TileKey(3, 4, 2)).endsWith("/GoogleMapsCompatible_Level9/3/2/4.jpg"))
        val radar = TileSource.radar("https://tilecache.rainviewer.com", "/v2/radar/1790636400")
        assertEquals("https://tilecache.rainviewer.com/v2/radar/1790636400/256/5/15/12/2/1_1.png", radar.url(TileKey(5, 15, 12)))
        assertEquals(TileKey(7, 62, 50), radar.keyFor(k))
    }

    @Test fun rainViewerIndex() {
        val text = """{"version":"2.0","generated":1790636500,"host":"https://tilecache.rainviewer.com",
          "radar":{"past":[{"time":1790635800,"path":"/v2/radar/1790635800"},{"time":1790636400,"path":"/v2/radar/1790636400"}],"nowcast":[]}}"""
        assertEquals("https://tilecache.rainviewer.com" to "/v2/radar/1790636400", RainViewer.latest(text))
    }

    @Test fun openMeteo() {
        val text = """{"latitude":35.76,"longitude":-5.83,"current_units":{"temperature_2m":"°C"},
          "current":{"time":"2026-09-28T22:00","interval":900,"temperature_2m":21.4,"relative_humidity_2m":72,
          "weather_code":2,"wind_speed_10m":14.8,"wind_direction_10m":265,"precipitation":0.0,"cloud_cover":40}}"""
        val w = OpenMeteo.parse(text)
        assertEquals(21.4, w.tempC, 0.0)
        assertEquals(72, w.humidity)
        assertEquals("Partly cloudy", w.description)
        assertEquals(265.0, w.windFromDeg!!, 0.0)
        assertEquals(40, w.cloudPct)
        assertTrue(OpenMeteo.url(35.7595, -5.834).startsWith("https://api.open-meteo.com/v1/forecast?latitude=35.7595&longitude=-5.8340&current="))
    }
}

class SkyProjectionTest {
    // Phone upright, back camera facing north: device X = east, Y = up, Z = south.
    private val facingNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    @Test fun northHorizonIsTheCentre() {
        val p = SkyProjection.project(facingNorth, 0.0, 0.0, 0.0, 1000.0, 500.0, 900.0)!!
        assertEquals(500.0, p[0], 1e-6)
        assertEquals(900.0, p[1], 1e-6)
    }

    @Test fun upIsUpAndEastIsRight() {
        val up = SkyProjection.project(facingNorth, 0.0, 0.0, 10.0, 1000.0, 500.0, 900.0)!!
        assertTrue(up[1] < 900.0)
        assertEquals(500.0, up[0], 1e-6)
        val right = SkyProjection.project(facingNorth, 0.0, 10.0, 0.0, 1000.0, 500.0, 900.0)!!
        assertTrue(right[0] > 500.0)
        assertEquals(1000.0 * kotlin.math.tan(Math.toRadians(10.0)), right[0] - 500.0, 1e-6)
    }

    @Test fun behindIsHiddenAndDeclinationShifts() {
        assertEquals(null, SkyProjection.project(facingNorth, 0.0, 180.0, 0.0, 1000.0, 500.0, 900.0))
        assertEquals(null, SkyProjection.project(facingNorth, 0.0, 90.0, 0.0, 1000.0, 500.0, 900.0))
        // Magnetic north 2° west of true (declination -2°): true north appears 2° right of centre.
        val p = SkyProjection.project(facingNorth, -2.0, 0.0, 0.0, 1000.0, 500.0, 900.0)!!
        assertTrue(p[0] > 500.0)
    }
}
