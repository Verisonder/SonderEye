package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictsTest {

    @Test fun gdeltPoints() {
        val text = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Khartoum, Khartoum, Sudan","count":42,"shareimage":"",
                "html":"<a href=\"https://news.example/a?x=1&amp;y=2\" title=\"Shelling hits &quot;market&quot;\">Shelling hits &quot;market&quot;</a><BR><a href=\"https://news.example/b\">Army says</a><BR><a href=\"https://news.example/b\">Army says</a><BR>"},
               "geometry":{"type":"Point","coordinates":[32.5342,15.5881]}},
              {"type":"Feature","properties":{"count":3},"geometry":{"type":"Point","coordinates":[36.2,49.99]}}
            ]}
        """
        val c = Gdelt.parse(text)
        assertEquals(2, c.size)
        val k = c[0]
        assertEquals("Khartoum, Khartoum, Sudan", k.name)
        assertEquals(42, k.count)
        assertEquals(15.5881, k.lat, 1e-9); assertEquals(32.5342, k.lon, 1e-9)
        assertEquals(2, k.articles.size) // the repeated link once
        assertEquals("Shelling hits \"market\"", k.articles[0].title)
        assertEquals("https://news.example/a?x=1&y=2", k.articles[0].url)
        assertEquals("x:15.588,32.534", k.key)
        assertEquals("49.99, 36.20", c[1].name)
        assertTrue(Gdelt.URL.contains("theme:ARMEDCONFLICT") && Gdelt.URL.contains("format=GeoJSON"))
    }

    @Test fun gdeltErrorTextIsShown() {
        try {
            Gdelt.parse("Your query was too short or too long.\n")
            throw AssertionError("should fail")
        } catch (e: Json.ParseError) {
            assertEquals("Your query was too short or too long.", e.message)
        }
    }
}
