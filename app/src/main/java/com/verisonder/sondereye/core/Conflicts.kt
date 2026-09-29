package com.verisonder.sondereye.core

import java.util.Locale

/** A news story about a place. */
class Article(val title: String, val url: String)

/**
 * A place the world's news reports fighting in, from GDELT's event files. What the news
 * says, not a verified event.
 */
class Conflict(val lat: Double, val lon: Double, val name: String, val count: Int, val articles: List<Article>) {
    val key: String get() = "x:%.3f,%.3f".format(Locale.ROOT, lat, lon)
}

/**
 * GDELT's event files: every 15 minutes, the events its machines read in the world's news,
 * each with where it happened. GDELT's map service (GEO API) is down for good at the time of
 * writing (it answers 404), so the pins come from these files instead: fighting only
 * (CAMEO root codes 18 assault, 19 fight, 20 mass violence), placed at a town or city.
 */
object GdeltEvents {
    const val LAST_UPDATE = "https://data.gdeltproject.org/gdeltv2/lastupdate.txt"
    const val LAST_UPDATE_PLAIN = "http://data.gdeltproject.org/gdeltv2/lastupdate.txt"

    /** One conflict event: where, how many articles, and one of them. */
    class Event(val lat: Double, val lon: Double, val place: String, val articles: Int, val url: String)

    private val FIGHTING = setOf("18", "19", "20")

    /** The newest events file named in lastupdate.txt. */
    fun latestExport(text: String): String =
        text.lineSequence().map { it.trim().substringAfterLast(' ') }.firstOrNull { it.endsWith(".export.CSV.zip") }
            ?: throw Json.ParseError("No events file named")

    /** The files for the [n] quarter-hours up to and including [latest], newest first. */
    fun lastFiles(latest: String, n: Int): List<String> {
        val stamp = Regex("(\\d{14})\\.export\\.CSV\\.zip$").find(latest)?.groupValues?.get(1)
            ?: throw Json.ParseError("Unexpected events file name")
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val t = fmt.parse(stamp)!!.time
        val base = latest.removeSuffix("$stamp.export.CSV.zip")
        return (0 until n).map { i -> base + fmt.format(java.util.Date(t - i * 15 * 60_000L)) + ".export.CSV.zip" }
    }

    fun stampOf(url: String) = Regex("(\\d{14})\\.export").find(url)?.groupValues?.get(1) ?: url.hashCode().toString()

    /** The fighting events in one zipped export file. */
    fun parseZip(zip: ByteArray): List<Event> {
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            z.nextEntry ?: throw Json.ParseError("Empty events file")
            return parseCsv(z.readBytes().toString(Charsets.UTF_8))
        }
    }

    fun parseCsv(text: String): List<Event> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 61 || f[28] !in FIGHTING) return@mapNotNull null
        if (f[51] != "3" && f[51] != "4") return@mapNotNull null // a town or city, not a whole country or region
        val lat = f[56].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[57].toDoubleOrNull() ?: return@mapNotNull null
        Event(lat, lon, f[52], f[33].toIntOrNull() ?: 1, f[60])
    }.toList()

    /** Events as short lines for the phone's cache, and back. */
    fun encode(events: List<Event>) = events.joinToString("\n") { "${it.lat}\t${it.lon}\t${it.articles}\t${it.place.replace('\t', ' ')}\t${it.url}" }
    fun decode(text: String) = text.lineSequence().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 5) null else Event(f[0].toDoubleOrNull() ?: return@mapNotNull null, f[1].toDoubleOrNull() ?: return@mapNotNull null, f[3], f[2].toIntOrNull() ?: 1, f[4])
    }.toList()

    /** One pin per place: its articles added up, its stories newest first. */
    fun places(events: List<Event>): List<Conflict> =
        events.groupBy { "%.3f,%.3f".format(Locale.ROOT, it.lat, it.lon) }.values.map { g ->
            val first = g.first()
            Conflict(
                first.lat, first.lon, first.place.ifBlank { "%.2f, %.2f".format(Locale.ROOT, first.lat, first.lon) },
                g.sumOf { it.articles },
                g.map { it.url }.filter { it.startsWith("http") }.distinct().take(10).map { Article(titleOf(it), it) },
            )
        }.sortedByDescending { it.count }

    /** A readable title from a story's address: "army-shells-market-in-x" → "Army shells market in x (site.com)". */
    fun titleOf(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val slug = url.substringAfter("://").substringAfter('/', "").substringBefore('?').trimEnd('/')
            .split('/').lastOrNull { seg -> seg.count { it == '-' || it == '_' } >= 2 }
            ?.substringBeforeLast('.')?.replace('-', ' ')?.replace('_', ' ')
            ?.split(' ')?.filter { w -> w.isNotBlank() && !(w.length > 5 && w.all { it.isDigit() }) }?.joinToString(" ")
        return if (slug.isNullOrBlank()) host else slug.replaceFirstChar { it.uppercase() } + " ($host)"
    }
}
