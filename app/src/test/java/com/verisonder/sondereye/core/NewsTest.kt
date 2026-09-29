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

    @Test fun atomAndIso() {
        val atom = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>X</title>
<entry><title>New phone released</title><link rel="alternate" href="https://ex.com/a"/>
<summary type="html">&lt;p&gt;It is thin. It is fast.&lt;/p&gt;</summary><published>2026-09-29T08:00:00+02:00</published></entry></feed>"""
        val s = News.parseRss("Ex", atom)
        assertEquals(1, s.size)
        assertEquals("https://ex.com/a", s[0].link)
        assertEquals("It is thin. It is fast.", s[0].summary)
        assertEquals(Eonet.isoMs("2026-09-29T06:00:00Z"), s[0].timeMs)
        assertEquals(Eonet.isoMs("2026-09-29T07:15:00Z"), News.iso("2026-09-29T07:15:00.123Z"))
    }

    @Test fun sourcesAndFilters() {
        val p = BriefPrefs(sources = setOf("bbc", "hn"), custom = listOf("https://www.example.org/feed.xml"))
        assertEquals(listOf("BBC World", "Hacker News", "example.org"), News.sourcesFor(p).map { it.name })
        val st = listOf(Story("A", "Morocco wins the cup", "", "", 0), Story("A", "Election in France", "", "", 0), Story("A", "Tech in Rabat", "Morocco startups", "", 0))
        assertEquals(2, News.filter(st, "morocco", "").size)
        assertEquals(listOf("Election in France"), News.filter(st, "", "morocco").map { it.title })
        assertEquals(3, News.filter(st, "", "").size)
    }

    @Test fun briefCacheRoundTrip() {
        val st = listOf(Story("BBC", "Title \"quoted\"", "Line one.\nLine two.", "https://x", 1790640000000L), Story("HN", "No time", "", "", null))
        val text = BriefCache.encode("2026-09-29", 1790641000000L, "Calm day.\n- point", st)
        val back = BriefCache.decode(text)!!
        assertEquals("2026-09-29", back.day)
        assertEquals(1790641000000L, back.atMs)
        assertEquals("Calm day.\n- point", back.summary)
        assertEquals(st, back.stories)
        assertNull(BriefCache.decode(BriefCache.encode("d", 0, null, emptyList()))!!.summary)
        assertNull(BriefCache.decode("garbage"))
    }

    @Test fun gemini() {
        val body = Gemini.request(listOf(Story("BBC", "T", "S", "l", 0)), "22 °C, clear", "Tangier")
        val prompt = ((((Json.parse(body) as Map<*, *>)["contents"] as List<*>)[0] as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>
        assertTrue((prompt["text"] as String).contains("- T: S (BBC)"))
        val custom = Gemini.request(listOf(Story("BBC", "T", "S", "l", 0)), null, null, BriefPrefs(length = 3, language = "French", bullets = true, focus = "Morocco"))
        val t = (((((Json.parse(custom) as Map<*, *>)["contents"] as List<*>)[0] as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>)["text"] as String
        assertTrue(t.contains("in French") && t.contains("at most 3 short bullet points") && t.contains("\"Morocco\""))
        val ok = """{"candidates":[{"content":{"parts":[{"text":"Calm day. "},{"text":"Storm offshore."}]}}]}"""
        assertEquals("Calm day. Storm offshore.", Gemini.parse(ok))
        assertTrue(body.contains("\"thinkingBudget\":0") && body.contains("\"maxOutputTokens\":2048"))
        assertTrue(!Gemini.request(emptyList(), null, null, model = "gemini-flash-latest").contains("thinkingBudget"))
        val cut = """{"candidates":[{"content":{"parts":[{"text":"Calm day. Storm offshore. Today's weather is 28 and"}]},"finishReason":"MAX_TOKENS"}]}"""
        assertEquals("Calm day. Storm offshore.", Gemini.parse(cut))
        val thought = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"Calm day."}]},"finishReason":"STOP"}]}"""
        assertEquals("Calm day.", Gemini.parse(thought))
        try { Gemini.parse("""{"error":{"code":400,"message":"API key not valid"}}"""); throw AssertionError() } catch (e: Json.ParseError) {
            assertEquals("API key not valid", e.message)
        }
    }
}
