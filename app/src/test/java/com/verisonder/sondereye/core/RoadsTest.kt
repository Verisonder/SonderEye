package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsTest {

    @Test fun parseAndClasses() {
        val text = """{"elements":[
            {"type":"way","id":1,"tags":{"highway":"primary"},"geometry":[{"lat":35.78,"lon":-5.81},{"lat":35.781,"lon":-5.811},{"lat":35.782,"lon":-5.812}]},
            {"type":"way","id":2,"tags":{"highway":"footway"},"geometry":[{"lat":35.78,"lon":-5.81},{"lat":35.781,"lon":-5.811}]},
            {"type":"way","id":3,"tags":{"highway":"residential"},"geometry":[{"lat":35.78,"lon":-5.81}]},
            {"type":"way","id":4,"tags":{"highway":"trunk_link"},"geometry":[{"lat":35.78,"lon":-5.81},null,{"lat":35.79,"lon":-5.82}]}
        ]}"""
        val roads = OsmRoads.parse(text)
        assertEquals(2, roads.size) // no footway, no one-point way
        assertEquals(1, roads[0].cls)
        assertEquals(0, roads[1].cls)
        assertTrue(OsmRoads.query(35.7, -5.9, 35.8, -5.7).contains("residential"))
    }

    @Test fun ribbonsAreTwoTrianglesPerSegment() {
        val roads = listOf(Road(1, listOf(doubleArrayOf(35.78, -5.81), doubleArrayOf(35.781, -5.811), doubleArrayOf(35.782, -5.812))))
        val origin = Geo.ecef(35.78, -5.81)
        val r = OsmRoads.ribbons(roads, origin, 2.0)
        assertEquals(OsmRoads.CLASS_COUNT, r.size)
        assertEquals(2 * 6 * OsmRoads.FLOATS_PER_VERTEX, r[1].size)
        assertEquals(0, r[0].size)
        // First vertex: the first point, 2 m up, relative to the origin; its partner is the second point.
        val first = V3(r[1][0].toDouble(), r[1][1].toDouble(), r[1][2].toDouble())
        assertEquals(2.0, first.len(), 0.01)
        val other = V3(r[1][3].toDouble(), r[1][4].toDouble(), r[1][5].toDouble())
        assertTrue(other.len() > 100 && other.len() < 200) // about 143 m away
        assertEquals(1f, r[1][6])
    }
}
