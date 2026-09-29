package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsTest {

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

    @Test fun vectorTileRoads() {
        // Written by the mapbox-vector-tile library: a water layer, and roads of four kinds.
        val hex = "1a2d0a0577617465721211120200001803220909000012140000140f1a05636c61737322050a0373656128802078021ab9010a0e7472616e73706f72746174696f6e12161204000001011802220c090000128020802080208020121a120200021802221209c801c8010ac8010009c80190030a00c80112101202000318022208090080400a14bf01121012040004020518022206090a0a0a02021a05636c6173731a046e616d651a076272756e6e656c22090a077072696d61727922030a017822070a056d696e6f7222060a047261696c22090a077365727669636522080a0674756e6e656c2880207802"
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val (x, y) = RoadTiles.tilesFor(35.78, -5.82, 35.78, -5.82).single()
        val roads = RoadTiles.roads(bytes, x, y)
        assertEquals(listOf(1, 4, 4, 5), roads.map { it.cls }) // rail left out, both parts of the minor road
        assertEquals(3, roads[0].pts.size)
        // The primary road runs corner to corner: from the tile's north-west to its south-east.
        val nw = roads[0].pts.first(); val se = roads[0].pts.last()
        assertTrue(nw[0] > 35.78 && se[0] < 35.78 && nw[1] < -5.82 && se[1] > -5.82)
        assertEquals(0.0219, se[1] - nw[1], 0.0001) // a zoom-14 tile is 360/16384 degrees wide
        assertEquals(4, RoadTiles.tilesFor(35.77, -5.83, 35.79, -5.81).size)
        // A gzipped tile reads the same.
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
        assertEquals(4, RoadTiles.roads(gz, x, y).size)
    }
}
