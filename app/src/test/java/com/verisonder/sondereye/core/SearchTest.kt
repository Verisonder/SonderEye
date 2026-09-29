package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test fun nominatim() {
        val text = """[{"place_id":1,"lat":"35.7672","lon":"-5.7997","name":"Tangier","display_name":"Tangier, Tanger-Assilah, Morocco"},
                       {"place_id":2,"lat":"bad","lon":"1"}]"""
        val p = Nominatim.parse(text)
        assertEquals(1, p.size)
        assertEquals("Tangier", p[0].name)
        assertEquals(35.7672, p[0].lat, 0.0)
        assertTrue(Nominatim.url("Café Hafa").endsWith("q=Caf%C3%A9+Hafa"))
    }

    @Test fun overpassCameras() {
        val text = """{"version":0.6,"elements":[
          {"type":"node","id":123,"lat":35.77,"lon":-5.81,"tags":{"man_made":"surveillance","surveillance:type":"ALPR","operator":"City","camera:direction":"90"}},
          {"type":"node","id":124,"lat":35.78,"lon":-5.82,"tags":{"man_made":"surveillance","surveillance:type":"camera"}}]}"""
        val c = Overpass.parseCameras(text)
        assertEquals(2, c.size)
        assertTrue(c[0].alpr)
        assertEquals(90.0, c[0].direction!!, 0.0)
        assertEquals(false, c[1].alpr)
        assertTrue(Overpass.cameraQuery(35.7, -5.9, 35.8, -5.7).startsWith("data=%5Bout%3Ajson%5D"))
    }

    @Test fun forecast() {
        val text = """{"current":{"time":"2026-09-29T14:15","temperature_2m":24.0,"weather_code":1},
          "hourly":{"time":["2026-09-29T13:00","2026-09-29T14:00","2026-09-29T15:00"],"temperature_2m":[23.0,24.0,25.0],
                    "precipitation_probability":[0,5,10],"weather_code":[1,1,2]},
          "daily":{"time":["2026-09-29","2026-09-30"],"temperature_2m_max":[26.0,27.0],"temperature_2m_min":[18.0,19.0],
                   "precipitation_probability_max":[10,60],"weather_code":[2,61]}}"""
        val (now, f) = ForecastApi.parse(text)
        assertEquals(24.0, now.tempC, 0.0)
        assertEquals("2026-09-29T14:00", f.hours[0].timeLocal) // starts at the current hour
        assertEquals(2, f.hours.size)
        assertEquals(60, f.days[1].rainChance)
        assertEquals(61, f.days[1].code)
    }

    @Test fun radarFrames() {
        val (host, frames) = RainViewer.frames("""{"host":"https://h","radar":{"past":[{"time":1790636400,"path":"/v2/radar/9f3a1c"},{"path":"/b"}]}}""")
        assertEquals("https://h", host)
        assertEquals("/v2/radar/9f3a1c", RainViewer.framePath(frames[0]))
        assertEquals(1790636400000L, RainViewer.frameTimeMs(frames[0]))
        assertEquals("/b", RainViewer.framePath(frames[1]))
        assertEquals(null, RainViewer.frameTimeMs(frames[1])) // no time given: unknown, not 1970
    }

    @Test fun deadReckoning() {
        // 480 kt due east for one hour at the equator: 480 nm = 8° of longitude.
        val p = Motion.ahead(0.0, 10.0, 90.0, 480.0, 3600.0)
        assertEquals(0.0, p[0], 1e-6)
        assertEquals(18.0, p[1], 0.01)
        val n = Motion.ahead(35.0, -5.0, 0.0, 60.0, 3600.0) // 60 nm north = 1° of latitude
        assertEquals(36.0, n[0], 0.01)
    }
}
