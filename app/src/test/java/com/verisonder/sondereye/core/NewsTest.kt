package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsTest {
    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>BBC News</title>
<item><title><![CDATA[Storm Gonzalo heads for the Azores]]></title>
<description><![CDATA[<p>The tropical storm is expected to strengthen. Residents have been told to prepare. More follows here with a very long tail.</p>]]></description>
<link>https://www.bbc.co.uk/news/a1</link><pubDate>Tue, 29 Sep 2026 07:15:00 GMT</pubDate></item>
<item><title>Talks resume in Geneva &amp; Rome</title><description>Delegates met on Monday.</description>
<link>https://www.bbc.co.uk/news/a2</link><pubDate>Mon, 28 Sep 2026 22:00:00 +0200</pubDate></item>
<item><description>No title here</description></item>
</channel></rss>"""

    @Test fun parsesItems() {
        val s = News.parseRss("BBC World", rss)
        assertEquals(2, s.size)
        assertEquals("Storm Gonzalo heads for the Azores", s[0].title)
        assertEquals("The tropical storm is expected to strengthen. Residents have been told to prepare.", s[0].summary)
        assertEquals("Talks resume in Geneva & Rome", s[1].title)
        assertEquals(Eonet.isoMs("2026-09-29T07:15:00Z"), s[0].timeMs)
        assertEquals(Eonet.isoMs("2026-09-28T20:00:00Z"), s[1].timeMs) // +0200 shifted to UTC
    }

    @Test fun rejectsNonFeeds() {
        try { News.parseRss("x", "<html>403</html>"); throw AssertionError("accepted") } catch (e: Json.ParseError) { }
        assertNull(News.rfc822("yesterday"))
    }

    @Test fun todayKeepsRecentAndDropsDuplicates() {
        val now = Eonet.isoMs("2026-09-29T09:00:00Z")!!
        val h = 3_600_000L
        val all = listOf(
            Story("A", "Storm Gonzalo heads for the Azores", "", "", now - h),
            Story("B", "Tropical storm Gonzalo heads towards Azores islands", "", "", now - 2 * h),
            Story("A", "Talks resume in Geneva", "", "", now - 3 * h),
            Story("C", "Old news from last week", "", "", now - 100 * h),
        )
        val t = News.today(all, now)
        assertEquals(listOf("Storm Gonzalo heads for the Azores", "Talks resume in Geneva"), t.map { it.title })
    }

    @Test fun gemini() {
        val body = Gemini.request(listOf(Story("BBC", "T", "S", "l", 0)), "22 °C, clear", "Tangier")
        val prompt = ((((Json.parse(body) as Map<*, *>)["contents"] as List<*>)[0] as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>
        assertTrue((prompt["text"] as String).contains("- T: S (BBC)"))
        val ok = """{"candidates":[{"content":{"parts":[{"text":"Calm day. "},{"text":"Storm offshore."}]}}]}"""
        assertEquals("Calm day. Storm offshore.", Gemini.parse(ok))
        try { Gemini.parse("""{"error":{"code":400,"message":"API key not valid"}}"""); throw AssertionError() } catch (e: Json.ParseError) {
            assertEquals("API key not valid", e.message)
        }
    }
}
