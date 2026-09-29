package com.verisonder.sondereye.core

/** One story from a publisher's RSS feed: their own headline and summary. */
data class Story(val source: String, val title: String, val summary: String, val link: String, val timeMs: Long?)

class NewsSource(val id: String, val name: String, val url: String, val topic: String)

/** How the user wants the day's brief. */
data class BriefPrefs(
    /** Ids of the built-in sources that are on. */
    val sources: Set<String> = setOf("bbc", "aljazeera", "mwn"),
    /** The user's own feed addresses (RSS or Atom). */
    val custom: List<String> = emptyList(),
    val stories: Int = 12,
    /** Only stories mentioning one of these words (empty: all). */
    val include: String = "",
    /** Never stories mentioning one of these words. */
    val exclude: String = "",
    val weather: Boolean = true,
    // The written summary (Gemini).
    val length: Int = 5,
    val language: String = "English",
    val bullets: Boolean = false,
    /** Free text: "focus on Morocco and technology". */
    val focus: String = "",
)

object News {
    /** Free, keyless publisher feeds. */
    val SOURCES = listOf(
        NewsSource("bbc", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "World"),
        NewsSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "World"),
        NewsSource("guardian", "The Guardian", "https://www.theguardian.com/world/rss", "World"),
        NewsSource("npr", "NPR", "https://feeds.npr.org/1001/rss.xml", "World"),
        NewsSource("france24", "France 24", "https://www.france24.com/en/rss", "World"),
        NewsSource("mwn", "Morocco World News", "https://www.moroccoworldnews.com/feed/", "Morocco"),
        NewsSource("hespress", "Hespress (French)", "https://fr.hespress.com/feed", "Morocco"),
        NewsSource("bbcbusiness", "BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml", "Business"),
        NewsSource("bbcscience", "BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "Science"),
        NewsSource("techcrunch", "TechCrunch", "https://techcrunch.com/feed/", "Technology"),
        NewsSource("hn", "Hacker News", "https://hnrss.org/frontpage", "Technology"),
        NewsSource("bbcsport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml", "Sport"),
    )

    /** The feeds to read for these preferences: chosen built-ins, then the user's own. */
    fun sourcesFor(p: BriefPrefs): List<NewsSource> =
        SOURCES.filter { it.id in p.sources } +
            p.custom.filter { it.isNotBlank() }.map { url ->
                val host = runCatching { java.net.URI(url.trim()).host?.removePrefix("www.") }.getOrNull() ?: url
                NewsSource("custom:$url", host, url.trim(), "Yours")
            }

    /** Keep stories matching the include words (if any) and none of the exclude words. */
    fun filter(stories: List<Story>, include: String, exclude: String): List<Story> {
        fun words(s: String) = s.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.length >= 2 }
        val inc = words(include)
        val exc = words(exclude)
        return stories.filter { st ->
            val text = (st.title + " " + st.summary).lowercase()
            (inc.isEmpty() || inc.any { it in text }) && exc.none { it in text }
        }
    }

    /** Items of an RSS 2.0 or Atom feed. A small reader: feeds are simple, and this stays testable off-device. */
    fun parseRss(source: String, xml: String): List<Story> {
        if (xml.contains("<feed") && xml.contains("<entry")) return parseAtom(source, xml)
        if (!xml.contains("<rss") && !xml.contains("<channel")) throw Json.ParseError("Not an RSS or Atom feed")
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

    private fun parseAtom(source: String, xml: String): List<Story> {
        val out = ArrayList<Story>()
        for (m in Regex("<entry\\b[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val body = m.groupValues[1]
            val title = text(tag(body, "title")) ?: continue
            val link = Regex("<link\\b[^>]*href=\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
            val summary = text(tag(body, "summary") ?: tag(body, "content"))?.let(::firstSentences) ?: ""
            val time = (tag(body, "published") ?: tag(body, "updated"))?.let { text(it) }?.let { iso(it) }
            out.add(Story(source, title, summary, link, time))
        }
        return out
    }

    /** "2026-09-29T07:15:00Z" or with "+02:00" → epoch ms. */
    fun iso(s: String): Long? = runCatching {
        val base = Eonet.isoMs(s.take(19) + "Z")!!
        val z = s.drop(19).dropWhile { it == '.' || it.isDigit() }
        val off = if (z.startsWith("+") || z.startsWith("-")) {
            val sign = if (z[0] == '-') -1 else 1
            sign * (z.substring(1, 3).toInt() * 60 + z.substring(4, 6).toInt())
        } else 0
        base - off * 60_000L
    }.getOrNull()

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
        // Some feeds escape their HTML (&lt;p&gt;): decoding revealed tags, drop them too.
        s = s.replace(Regex("<[^>]+>"), " ")
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

    fun request(stories: List<Story>, weather: String?, place: String?, p: BriefPrefs = BriefPrefs(), model: String = MODELS[0]): String {
        val list = stories.joinToString("\n") { "- ${it.title}: ${it.summary} (${it.source})" }
        val prompt = buildString {
            append("Write a brief of today's news for one reader")
            if (place != null) append(" in $place")
            append(", in ${p.language}. ")
            if (p.bullets) append("Use at most ${p.length} short bullet points, one idea each. ")
            else append("Use at most ${p.length} sentences in one paragraph. ")
            append("Calm and factual. Use only the headlines and summaries below; ")
            append("do not add facts, numbers or opinions that are not in them. No title.")
            if (p.focus.isNotBlank()) append(" The reader asked: \"${p.focus.trim().take(200)}\" — follow that where the headlines allow.")
            append("\n\n")
            if (weather != null) append("Weather today: $weather\n\n")
            append("Headlines:\n").append(list)
        }
        // 2.5 Flash thinks before it writes, and the thinking counts against the output limit:
        // with a small limit it stopped mid-sentence. No thinking is needed to summarise.
        val thinking = if (model.startsWith("gemini-2.5")) ",\"thinkingConfig\":{\"thinkingBudget\":0}" else ""
        return "{\"contents\":[{\"parts\":[{\"text\":" + Json.str(prompt) + "}]}]," +
            "\"generationConfig\":{\"temperature\":0.3,\"maxOutputTokens\":2048$thinking}}"
    }

    /**
     * What happened at a place in the news for fighting, from its stories. With [readLinks],
     * Gemini may open the links (its URL-context tool) rather than go by the titles alone.
     */
    fun explainRequest(place: String, articles: List<Article>, language: String, model: String = MODELS[0], readLinks: Boolean = true): String {
        val list = articles.take(10).joinToString("\n") { "- ${it.title}: ${it.url}" }
        val prompt = buildString {
            append("In 3 to 5 short sentences, in $language, explain what happened in or near $place according to these news stories. ")
            append("Say who was involved, what happened and when, as the stories report it. ")
            append("If the stories disagree, or say little, say so plainly. Do not guess or add anything they do not say. No title.")
            append("\n\nStories:\n").append(list)
        }
        val tools = if (readLinks) ",\"tools\":[{\"url_context\":{}}]" else ""
        val thinking = if (model.startsWith("gemini-2.5")) ",\"thinkingConfig\":{\"thinkingBudget\":0}" else ""
        return "{\"contents\":[{\"parts\":[{\"text\":" + Json.str(prompt) + "}]}]$tools," +
            "\"generationConfig\":{\"temperature\":0.2,\"maxOutputTokens\":1024$thinking}}"
    }

    fun parse(text: String): String {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        (root["error"] as? Map<*, *>)?.let { throw Json.ParseError((it["message"] as? String) ?: "Gemini error") }
        val cand = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val parts = ((cand?.get("content") as? Map<*, *>)?.get("parts") as? List<*>)
            ?: throw Json.ParseError("No text in the answer")
        val text = parts
            .filter { (it as? Map<*, *>)?.get("thought") != true } // its reasoning, if shown, is not the brief
            .mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("").trim()
            .ifEmpty { throw Json.ParseError("Empty answer") }
        if (cand["finishReason"] != "MAX_TOKENS") return text
        // Cut off: keep the whole sentences (or bullets), never half of one.
        val end = maxOf(text.lastIndexOf(". "), text.lastIndexOf(".\n"), text.lastIndexOf('\n'), if (text.endsWith(".")) text.length - 1 else -1)
        if (end <= 0) throw Json.ParseError("Gemini's answer was cut off; tap Regenerate")
        return text.substring(0, end + 1).trim()
    }
}

/** The day's brief as saved on the phone, so it is made once a day, not on every open. */
object BriefCache {
    class Saved(val day: String, val atMs: Long, val summary: String?, val stories: List<Story>)

    fun encode(day: String, atMs: Long, summary: String?, stories: List<Story>): String = buildString {
        append("{\"day\":").append(Json.str(day))
        append(",\"at\":").append(atMs)
        append(",\"summary\":").append(if (summary == null) "null" else Json.str(summary))
        append(",\"stories\":[")
        stories.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"src\":").append(Json.str(s.source))
            append(",\"t\":").append(Json.str(s.title))
            append(",\"sum\":").append(Json.str(s.summary))
            append(",\"l\":").append(Json.str(s.link))
            append(",\"ms\":").append(s.timeMs?.toString() ?: "null").append('}')
        }
        append("]}")
    }

    fun decode(text: String): Saved? = runCatching {
        val m = Json.parse(text) as Map<*, *>
        Saved(
            m["day"] as String,
            (m["at"] as Double).toLong(),
            m["summary"] as? String,
            (m["stories"] as List<*>).map {
                val s = it as Map<*, *>
                Story(s["src"] as String, s["t"] as String, s["sum"] as String, s["l"] as String, (s["ms"] as? Double)?.toLong())
            },
        )
    }.getOrNull()
}
