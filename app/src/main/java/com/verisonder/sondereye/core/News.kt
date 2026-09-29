package com.verisonder.sondereye.core

/** One story from a publisher's RSS feed: their own headline and summary. */
data class Story(val source: String, val title: String, val summary: String, val link: String, val timeMs: Long?)

class NewsSource(val name: String, val url: String)

object News {
    /** Free, keyless publisher feeds: world news, and Morocco. */
    val SOURCES = listOf(
        NewsSource("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        NewsSource("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        NewsSource("Morocco World News", "https://www.moroccoworldnews.com/feed/"),
    )

    /** Items of an RSS 2.0 feed. A small reader: feeds are simple, and this stays testable off-device. */
    fun parseRss(source: String, xml: String): List<Story> {
        if (!xml.contains("<rss") && !xml.contains("<channel")) throw Json.ParseError("Not an RSS feed")
        val out = ArrayList<Story>()
        for (m in Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val body = m.groupValues[1]
            val title = text(tag(body, "title")) ?: continue
            val link = text(tag(body, "link")) ?: ""
            val summary = text(tag(body, "description"))?.let(::firstSentences) ?: ""
            out.add(Story(source, title, summary, link, tag(body, "pubDate")?.let { rfc822(text(it) ?: "") }))
        }
        return out
    }

    private fun tag(body: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)

    /** CDATA unwrapped, HTML tags dropped, entities decoded, whitespace collapsed. */
    fun text(raw: String?): String? {
        raw ?: return null
        var s = raw.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&#8217;", "’").replace("&#8216;", "‘")
            .replace("&#8220;", "“").replace("&#8221;", "”").replace("&#8211;", "–").replace("&#8230;", "…")
        s = Regex("&#(\\d+);").replace(s) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        s = s.replace("&amp;", "&")
        return s.replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    /** At most two sentences, at most 220 characters: a summary, not the article. */
    fun firstSentences(s: String): String {
        val parts = Regex("(?<=[.!?])\\s+").split(s)
        var out = parts.first()
        if (parts.size > 1 && out.length + parts[1].length < 220) out += " " + parts[1]
        return if (out.length > 220) out.take(217).trimEnd() + "…" else out
    }

    /** "Tue, 29 Sep 2026 07:15:00 GMT" or "+0000" → epoch ms; null when unreadable. */
    fun rfc822(s: String): Long? = runCatching {
        val m = Regex("(\\d{1,2}) (\\w{3}) (\\d{4}) (\\d{2}):(\\d{2})(?::(\\d{2}))? ?([+-]\\d{4}|GMT|UT|UTC|Z)?").find(s)!!
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val mon = months.indexOf(m.groupValues[2]) + 1
        require(mon > 0)
        val iso = "%s-%02d-%02dT%s:%s:%sZ".format(m.groupValues[3], mon, m.groupValues[1].toInt(), m.groupValues[4], m.groupValues[5], m.groupValues[6].ifEmpty { "00" })
        val base = Eonet.isoMs(iso)!!
        val z = m.groupValues[7]
        val offMin = if (z.startsWith("+") || z.startsWith("-")) {
            val sign = if (z[0] == '-') -1 else 1
            sign * (z.substring(1, 3).toInt() * 60 + z.substring(3, 5).toInt())
        } else 0
        base - offMin * 60_000L
    }.getOrNull()

    /**
     * The day's stories: the last 24 hours, newest first, near-duplicates (the same story
     * from two outlets) kept once, at most [max].
     */
    fun today(all: List<Story>, nowMs: Long, max: Int = 12): List<Story> {
        val recent = all.filter { it.timeMs == null || nowMs - it.timeMs < 24 * 3_600_000L }
            .sortedByDescending { it.timeMs ?: 0L }
        val out = ArrayList<Story>()
        for (s in recent) {
            val words = s.title.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
            val dup = out.any { o ->
                val ow = o.title.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
                words.isNotEmpty() && (words intersect ow).size * 2 >= minOf(words.size, ow.size).coerceAtLeast(2)
            }
            if (!dup) out.add(s)
            if (out.size >= max) break
        }
        return out
    }
}

/**
 * Optional written brief by Google Gemini (free tier; the user's own key). The model only
 * sees the headlines and publisher summaries; it is asked not to add anything beyond them.
 */
object Gemini {
    val MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest")

    fun url(model: String, key: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" + java.net.URLEncoder.encode(key.trim(), "UTF-8")

    fun request(stories: List<Story>, weather: String?, place: String?): String {
        val list = stories.joinToString("\n") { "- ${it.title}: ${it.summary} (${it.source})" }
        val prompt = buildString {
            append("Write a short morning brief in plain English for one reader")
            if (place != null) append(" in $place")
            append(". Five sentences at most, calm and factual. Use only the headlines and summaries below; ")
            append("do not add facts, numbers or opinions that are not in them. No title, no bullet points.\n\n")
            if (weather != null) append("Weather today: $weather\n\n")
            append("Headlines:\n").append(list)
        }
        return "{\"contents\":[{\"parts\":[{\"text\":" + Json.str(prompt) + "}]}],\"generationConfig\":{\"temperature\":0.3,\"maxOutputTokens\":400}}"
    }

    fun parse(text: String): String {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        (root["error"] as? Map<*, *>)?.let { throw Json.ParseError((it["message"] as? String) ?: "Gemini error") }
        val parts = (((root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>)?.get("parts") as? List<*>
            ?: throw Json.ParseError("No text in the answer")
        return parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("").trim()
            .ifEmpty { throw Json.ParseError("Empty answer") }
    }
}
