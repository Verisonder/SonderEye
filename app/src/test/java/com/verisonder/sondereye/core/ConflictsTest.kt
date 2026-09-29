package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictsTest {

    private fun row(root: String, geoType: String, lat: String, lon: String, place: String, articles: String, url: String): String {
        val f = MutableList(61) { "" }
        f[28] = root; f[33] = articles; f[51] = geoType; f[52] = place; f[56] = lat; f[57] = lon; f[60] = url
        return f.joinToString("\t")
    }

    @Test fun gdeltEventFiles() {
        val latest = GdeltEvents.latestExport(
            "150383 297a16b493de7cf6ca809a7cc31d0b93 http://data.gdeltproject.org/gdeltv2/20260929123000.export.CSV.zip\n" +
                "318084 bb27f78ba45f69a17ea6ed7755e9f8ff http://data.gdeltproject.org/gdeltv2/20260929123000.mentions.CSV.zip\n",
        )
        assertEquals("http://data.gdeltproject.org/gdeltv2/20260929123000.export.CSV.zip", latest)
        val files = GdeltEvents.lastFiles(latest, 3)
        assertEquals("http://data.gdeltproject.org/gdeltv2/20260929120000.export.CSV.zip", files[2])
        assertEquals("20260929121500", GdeltEvents.stampOf(files[1]))
        // Midnight is crossed correctly.
        assertEquals("20260928234500", GdeltEvents.stampOf(GdeltEvents.lastFiles("http://x/20260929000000.export.CSV.zip", 2)[1]))

        val csv = listOf(
            row("19", "4", "15.5881", "32.5342", "Khartoum, Khartoum, Sudan", "6", "https://news.example/world/army-shells-market-in-khartoum-2026"),
            row("18", "4", "15.5881", "32.5342", "Khartoum, Khartoum, Sudan", "2", "https://www.other.example/a/b/"),
            row("19", "1", "15.0", "30.0", "Sudan", "9", "https://x.example/y"), // country only: left out
            row("04", "4", "48.85", "2.35", "Paris, France", "3", "https://x.example/talks"), // not fighting
            "too\tshort",
        ).joinToString("\n")
        val events = GdeltEvents.parseCsv(csv)
        assertEquals(2, events.size)
        val zip = java.io.ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z -> z.putNextEntry(java.util.zip.ZipEntry("x.CSV")); z.write(csv.toByteArray()); z.closeEntry() }
        }.toByteArray()
        assertEquals(2, GdeltEvents.parseZip(zip).size)
        assertEquals(2, GdeltEvents.decode(GdeltEvents.encode(events)).size)

        val places = GdeltEvents.places(events)
        assertEquals(1, places.size)
        assertEquals(8, places[0].count)
        assertEquals("Army shells market in khartoum 2026 (news.example)", places[0].articles[0].title)
        assertEquals("other.example", places[0].articles[1].title)
    }
}
