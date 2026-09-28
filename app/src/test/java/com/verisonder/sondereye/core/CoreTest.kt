package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreTest {

    // Shape copied from a real USGS summary feed, trimmed. Includes a null magnitude, a
    // quarry blast, a feature without geometry and one with an out-of-range latitude.
    private val feed = """
    {"type":"FeatureCollection",
     "metadata":{"generated":1727520000000,"url":"https://earthquake.usgs.gov/x","title":"USGS","status":200,"api":"1.10.3","count":5},
     "features":[
      {"type":"Feature","properties":{"mag":4.7,"place":"45 km SSW of Al Hoceïma, Morocco","time":1727510000000,"updated":1727511000000,"tz":null,"url":"https://earthquake.usgs.gov/earthquakes/eventpage/us7000abcd","tsunami":0,"alert":null,"type":"earthquake","title":"M 4.7 - x"},
       "geometry":{"type":"Point","coordinates":[-4.1,34.8,10]},"id":"us7000abcd"},
      {"type":"Feature","properties":{"mag":null,"place":"","time":1727515000000,"url":"u2","tsunami":1,"type":"earthquake"},
       "geometry":{"type":"Point","coordinates":[142.5,38.2,33.5]},"id":"jp1"},
      {"type":"Feature","properties":{"mag":1.9,"place":"5 km E of Quarry, CA","time":1727500000000,"url":"u3","tsunami":0,"type":"quarry blast"},
       "geometry":{"type":"Point","coordinates":[-117.1,34.0,-0.5]},"id":"ci3"},
      {"type":"Feature","properties":{"mag":2.0,"time":1727500000000},"geometry":null,"id":"broken1"},
      {"type":"Feature","properties":{"mag":2.0,"time":1727500000000},"geometry":{"type":"Point","coordinates":[10,95,1]},"id":"broken2"}
     ],
     "bbox":[-180,-90,-10,180,90,700]}
    """

    @Test fun jsonParsesNestedValues() {
        val v = Json.parse("""{"a":[1,2.5,-3e2,true,false,null],"b":{"c":"x\"y\\z\u00e9\n"}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), v["a"])
        assertEquals("x\"y\\zé\n", (v["b"] as Map<*, *>)["c"])
    }

    @Test fun jsonRejectsGarbage() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "nul", "\"abc", "{} x", "<html>")) {
            try {
                Json.parse(bad)
                fail("Accepted: $bad")
            } catch (e: Json.ParseError) { /* expected */ }
        }
    }

    @Test fun jsonStringEscapesForJavaScript() {
        assertEquals("\"a\\\"b\\\\c\\n\\u2028\\u003c/script>\\u0001\"", Json.str("a\"b\\c\n\u2028</script>\u0001"))
        // Round trip.
        val tricky = "Tōhoku \"quote\" \\ back\tslash\n</script>"
        assertEquals(tricky, Json.parse(Json.str(tricky)))
    }

    @Test fun feedParsesAndSkipsBrokenFeatures() {
        val r = Usgs.parse(feed)
        assertEquals(3, r.quakes.size)
        assertEquals(2, r.skipped)
        // Newest first.
        assertEquals(listOf("jp1", "us7000abcd", "ci3"), r.quakes.map { it.id })

        val m = r.quakes[1]
        assertEquals(4.7, m.mag!!, 0.0)
        assertEquals(34.8, m.lat, 0.0)
        assertEquals(-4.1, m.lon, 0.0)
        assertEquals(10.0, m.depthKm, 0.0)
        assertEquals(1727510000000L, m.timeMs)
        assertEquals("45 km SSW of Al Hoceïma, Morocco", m.place)
        assertFalse(m.tsunami)

        val jp = r.quakes[0]
        assertNull(jp.mag)
        assertEquals("Unnamed location", jp.place)
        assertTrue(jp.tsunami)

        assertEquals("quarry blast", r.quakes[2].type)
    }

    @Test fun feedRejectsNonFeeds() {
        for (bad in listOf("[]", """{"type":"Feature"}""", """{"type":"FeatureCollection"}""", "<html>503</html>")) {
            try {
                Usgs.parse(bad)
                fail("Accepted: $bad")
            } catch (e: Json.ParseError) { /* expected */ }
        }
        assertEquals(0, Usgs.parse("""{"type":"FeatureCollection","features":[]}""").quakes.size)
    }

    @Test fun feedUrls() {
        assertEquals(
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson",
            Usgs.feedUrl(MinMag.M25, Period.DAY),
        )
        assertEquals(
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_month.geojson",
            Usgs.feedUrl(MinMag.SIGNIFICANT, Period.MONTH),
        )
        assertEquals(
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson",
            Usgs.feedUrl(MinMag.ALL, Period.HOUR),
        )
    }

    @Test fun globePayloadIsValidJsonWithExpectedRows() {
        val quakes = Usgs.parse(feed).quakes
        val payload = Globe.quakePayload(quakes)
        val rows = Json.parse(payload) as List<*>
        assertEquals(3, rows.size)
        assertEquals(listOf("jp1", 38.2, 142.5, 0.0, 33.5), rows[0]) // null magnitude sent as 0
        assertEquals(listOf("us7000abcd", 34.8, -4.1, 4.7, 10.0), rows[1])
        assertEquals("[]", Globe.quakePayload(emptyList()))
    }

    @Test fun payloadSurvivesHostileIds() {
        val q = Quake("x\"</script><b>", 1.0, "p", "earthquake", 0, 1.0, 2.0, 3.0, "", false)
        val payload = Globe.quakePayload(listOf(q))
        assertFalse(payload.contains("</script>"))
        assertEquals("x\"</script><b>", ((Json.parse(payload) as List<*>)[0] as List<*>)[0])
    }

    @Test fun formatting() {
        assertEquals("M 4.7", Fmt.mag(4.66))
        assertEquals("M 5.0", Fmt.mag(5.0))
        assertEquals("M ?", Fmt.mag(null))
        val t = 1_000_000_000_000L
        assertEquals("Just now", Fmt.ago(t, t + 59_000))
        assertEquals("1 min ago", Fmt.ago(t, t + 60_000))
        assertEquals("59 min ago", Fmt.ago(t, t + 3_599_000))
        assertEquals("2 h ago", Fmt.ago(t, t + 7_200_000))
        assertEquals("3 d ago", Fmt.ago(t, t + 3 * 86_400_000L))
        assertEquals("10 km deep", Fmt.depth(10.4))
        assertNull(Fmt.typeLabel("earthquake"))
        assertEquals("Quarry blast", Fmt.typeLabel("quarry blast"))
    }
}
