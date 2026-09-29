package com.verisonder.sondereye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoTest {

    private val w = 1220
    private val h = 2712

    @Test fun ecefRoundTrip() {
        for ((lat, lon) in listOf(0.0 to 0.0, 35.77 to -5.8, -33.9 to 151.2, 84.0 to 179.0, -60.0 to -179.5)) {
            val ll = Geo.latLon(Geo.ecef(lat, lon))
            assertEquals(lat, ll[0], 1e-9)
            assertEquals(lon, ll[1], 1e-9)
        }
        assertEquals(EARTH_R, Geo.ecef(12.0, 34.0).len(), 1e-6)
    }

    @Test fun mercatorRoundTripAndEdges() {
        assertEquals(Geo.MAX_LAT, Geo.mercYToLat(0.0), 1e-9)
        assertEquals(0.0, Geo.mercYToLat(0.5), 1e-9)
        for (lat in listOf(-80.0, -35.0, 0.0, 35.77, 60.0)) assertEquals(lat, Geo.mercYToLat(Geo.latToMercY(lat)), 1e-9)
    }

    @Test fun wrapLon() {
        assertEquals(-170.0, Geo.wrapLon(190.0), 1e-9)
        assertEquals(170.0, Geo.wrapLon(-190.0), 1e-9)
        assertEquals(0.0, Geo.wrapLon(720.0), 1e-9)
    }

    @Test fun screenCentreIsTheTarget() {
        val cam = CameraState(35.77, -5.8, 50_000.0, 0.0)
        val v = cam.view(w, h)
        val p = v.pick(w / 2.0, h / 2.0)!!
        val ll = Geo.latLon(p)
        assertEquals(35.77, ll[0], 1e-6)
        assertEquals(-5.8, ll[1], 1e-6)
        val s = v.project(Geo.ecef(35.77, -5.8))!!
        assertEquals(w / 2.0, s[0], 1e-6)
        assertEquals(h / 2.0, s[1], 1e-6)
    }

    @Test fun northIsUpAndEastIsRight() {
        val v = CameraState(30.0, 10.0, 500_000.0, 0.0).view(w, h)
        val north = v.project(Geo.ecef(31.0, 10.0))!!
        val east = v.project(Geo.ecef(30.0, 11.0))!!
        assertTrue(north[1] < h / 2.0 && abs(north[0] - w / 2.0) < 1.0)
        assertTrue(east[0] > w / 2.0)
        // Heading 90: north now points to the left? The screen top shows east.
        val v90 = CameraState(30.0, 10.0, 500_000.0, 90.0).view(w, h)
        val east90 = v90.project(Geo.ecef(30.0, 11.0))!!
        assertTrue(east90[1] < h / 2.0)
    }

    @Test fun pickAndProjectAgree() {
        val v = CameraState(-10.0, 120.0, 3_000_000.0, 33.0).view(w, h)
        for ((px, py) in listOf(100.0 to 200.0, 900.0 to 2300.0, 610.0 to 1356.0)) {
            val p = v.pick(px, py)!!
            val s = v.project(p)!!
            assertEquals(px, s[0], 1e-6)
            assertEquals(py, s[1], 1e-6)
        }
    }

    @Test fun spaceAndFarSide() {
        val v = CameraState.HOME.view(w, h)
        assertNull(v.pick(2.0, 2.0)) // corner of the screen is space at whole-Earth view
        assertTrue(v.aboveHorizon(Geo.ecef(25.0, 0.0)))
        assertFalse(v.aboveHorizon(Geo.ecef(-25.0, 180.0)))
        val r = v.earthRadiusPx()
        assertTrue(r > 200 && r < w) // whole Earth fits across the screen
    }

    @Test fun mvpPutsTargetAtScreenCentre() {
        val cam = CameraState(35.77, -5.8, 5_000.0, 20.0)
        val v = cam.view(w, h)
        val target = Geo.ecef(35.77, -5.8)
        val m = v.mvp(target) // vertex at the origin of the object = the target
        val cx = m[12] / m[15]
        val cy = m[13] / m[15]
        val cz = m[14] / m[15]
        assertEquals(0.0, cx, 1e-9)
        assertEquals(0.0, cy, 1e-9)
        assertTrue(cz > -1 && cz < 1) // inside the depth range
    }

    @Test fun uvInAncestor() {
        val k = TileKey(5, 13, 22)
        val uv = k.uvIn(TileKey(3, 3, 5))
        assertEquals(0.25, uv[0], 0.0)
        assertEquals(0.25, uv[1], 0.0) // 13 - 12 = 1 of 4
        assertEquals(0.5, uv[2], 0.0)  // 22 - 20 = 2 of 4
        assertEquals(listOf(1.0, 0.0, 0.0), k.uvIn(k).toList())
        assertEquals(TileKey(4, 6, 11), k.parent())
    }

    @Test fun selectionWholeEarth() {
        val tiles = TileSelect.select(CameraState.HOME.view(w, h), 18, 384.0)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.z in 2..5 }) // 256 px images shown at no more than ~384 px
        // Only the visible side: nothing near the equator on the far side. (Over the pole,
        // longitude 180 is legitimately in view from 25N.)
        assertFalse(tiles.any { it.lonW >= 135.0 && it.latN <= 30.0 })
    }

    @Test fun selectionCloseUpIsFineAndCoversTheScreen() {
        val cam = CameraState(35.77, -5.8, 3_000.0, 0.0)
        val v = cam.view(w, h)
        val tiles = TileSelect.select(v, 18, 384.0)
        assertTrue(tiles.size in 4..180)
        assertTrue(tiles.any { it.z >= 14 })
        // Every sampled pixel lies in some selected tile: no holes.
        for (fx in 0..4) for (fy in 0..4) {
            val ll = Geo.latLon(v.pick(fx / 4.0 * w, fy / 4.0 * h)!!)
            assertTrue(tiles.any { ll[0] <= it.latN && ll[0] >= it.latS && ll[1] >= it.lonW && ll[1] <= it.lonE })
        }
    }

    @Test fun selectionAcrossTheDateLine() {
        val v = CameraState(0.0, 179.9, 200_000.0, 0.0).view(w, h)
        val tiles = TileSelect.select(v, 18, 384.0)
        assertTrue(tiles.any { it.lonE > 179.0 })
        assertTrue(tiles.any { it.lonW < -179.0 })
    }

    @Test fun meshShapeAndSkirts() {
        val k = TileKey(4, 7, 5)
        val n = TileMesh.segments(k.z)
        val v = TileMesh.vertices(k)
        assertEquals(((n + 1) * (n + 1) + 4 * (n + 1)) * 5, v.size)
        val idx = TileMesh.indices(n)
        assertEquals(n * n * 6 + 4 * n * 6, idx.size)
        val count = v.size / 5
        assertTrue(idx.all { it in 0 until count })
        // First vertex is the north-west corner, uv 0,0.
        val c = k.center()
        val nw = V3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()) + c
        val ll = Geo.latLon(nw)
        assertEquals(k.latN, ll[0], 1e-4)
        assertEquals(k.lonW, ll[1], 1e-4)
        assertEquals(0f, v[3]); assertEquals(0f, v[4])
    }

    @Test fun pickNearest() {
        val v = CameraState(30.0, 10.0, 2_000_000.0, 0.0).view(w, h)
        val lat = doubleArrayOf(30.0, 31.0, -30.0)
        val lon = doubleArrayOf(10.0, 10.0, -170.0) // last is on the far side
        assertEquals(0, Pick.nearest(v, lat, lon, w / 2.0 + 5, h / 2.0 - 5, 30.0))
        assertEquals(-1, Pick.nearest(v, lat, lon, 5.0, 5.0, 30.0))
    }

    @Test fun flyEndsExactlyAndTakesTheShortWay() {
        val a = CameraState(10.0, 170.0, 1_000_000.0, 0.0)
        val b = CameraState(20.0, -170.0, 600_000.0, 0.0)
        val end = Fly.lerp(a, b, 1.0)
        assertEquals(20.0, end.lat, 1e-9)
        assertEquals(-170.0, end.lon, 1e-9)
        assertEquals(600_000.0, end.alt, 1e-3)
        val mid = Fly.lerp(a, b, 0.5)
        assertTrue(abs(mid.lon) > 175.0) // crossed the date line, not the long way round
    }
}
