package com.verisonder.sondereye.core

import java.util.Locale

/** A news story about a place. */
class Article(val title: String, val url: String)

/**
 * A place the world's news reports fighting in: named in articles GDELT tags as armed
 * conflict. What the news says, not a verified event.
 */
class Conflict(val lat: Double, val lon: Double, val name: String, val count: Int, val articles: List<Article>) {
    val key: String get() = "x:%.3f,%.3f".format(Locale.ROOT, lat, lon)
}

object Gdelt {
    /**
     * GDELT GEO 2.0: every place named in articles about armed conflict in the last 24 hours,
     * countries themselves left out (their centre is not where the fighting is).
     */
    const val URL = "https://api.gdeltproject.org/api/v2/geo/geo?query=theme:ARMEDCONFLICT" +
        "&mode=PointData&format=GeoJSON&timespan=1440&maxpoints=1000&geores=1"

    private val link = Regex("<a\\s+href=\"([^\"]+)\"[^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Conflict> {
        val t = text.trim()
        // GDELT answers problems in plain text: that sentence is the message.
        if (!t.startsWith("{")) throw Json.ParseError(t.lineSequence().firstOrNull()?.take(120)?.ifEmpty { null } ?: "Empty answer")
        val root = Json.parse(t) as? Map<*, *> ?: throw Json.ParseError("Not a JSON object")
        val features = root["features"] as? List<*> ?: throw Json.ParseError("No features")
        return features.mapNotNull { f ->
            val fm = f as? Map<*, *> ?: return@mapNotNull null
            val coords = (fm["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*> ?: return@mapNotNull null
            val lon = coords.getOrNull(0) as? Double ?: return@mapNotNull null
            val lat = coords.getOrNull(1) as? Double ?: return@mapNotNull null
            val p = fm["properties"] as? Map<*, *> ?: emptyMap<String, Any>()
            val articles = link.findAll(p["html"] as? String ?: "").map {
                Article(unescape(it.groupValues[2]).trim().ifEmpty { it.groupValues[1] }, unescape(it.groupValues[1]))
            }.distinctBy { it.url }.toList()
            Conflict(
                lat, lon,
                (p["name"] as? String)?.trim()?.ifEmpty { null } ?: "%.2f, %.2f".format(Locale.ROOT, lat, lon),
                (p["count"] as? Double)?.toInt() ?: articles.size,
                articles,
            )
        }
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
