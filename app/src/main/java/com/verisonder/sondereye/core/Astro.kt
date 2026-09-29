package com.verisonder.sondereye.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun and Moon, low precision (about 0.01° and 0.5°): plenty to point at them in the sky
 * and to tell day from night. Earth-fixed positions in metres, like everything else.
 */
object Astro {
    private const val AU = 1.495978707e11

    private fun days(ms: Long) = ms / 86_400_000.0 + 2440587.5 - 2451545.0

    /** Equatorial (right ascension, declination, distance m) to Earth-fixed. */
    private fun toEcef(ra: Double, dec: Double, dist: Double, ms: Long): V3 {
        val h = ra - Sky.gmst(ms)
        return V3(dist * cos(dec) * cos(h), dist * cos(dec) * sin(h), dist * sin(dec))
    }

    private fun eclipticToEcef(lam: Double, beta: Double, dist: Double, n: Double, ms: Long): V3 {
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val x = cos(beta) * cos(lam)
        val y = cos(eps) * cos(beta) * sin(lam) - sin(eps) * sin(beta)
        val z = sin(eps) * cos(beta) * sin(lam) + cos(eps) * sin(beta)
        return toEcef(atan2(y, x), asin(z.coerceIn(-1.0, 1.0)), dist, ms)
    }

    fun sun(ms: Long): V3 {
        val n = days(ms)
        val l = Math.toRadians(280.460 + 0.9856474 * n)
        val g = Math.toRadians(357.528 + 0.9856003 * n)
        val lam = l + Math.toRadians(1.915 * sin(g) + 0.020 * sin(2 * g))
        val r = (1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2 * g)) * AU
        return eclipticToEcef(lam, 0.0, r, n, ms)
    }

    fun moon(ms: Long): V3 {
        val n = days(ms)
        val l = Math.toRadians(218.316 + 13.176396 * n)
        val m = Math.toRadians(134.963 + 13.064993 * n)
        val f = Math.toRadians(93.272 + 13.229350 * n)
        val lam = l + Math.toRadians(6.289 * sin(m))
        val beta = Math.toRadians(5.128 * sin(f))
        val dist = (385_001.0 - 20_905.0 * cos(m)) * 1000.0
        return eclipticToEcef(lam, beta, dist, n, ms)
    }

    /** True when [p] is in sunlight (outside Earth's cylindrical shadow). */
    fun sunlit(p: V3, ms: Long): Boolean {
        val u = sun(ms).norm()
        val along = p dot u
        if (along > 0) return true
        val perp = p - u * along
        return perp.len() > EARTH_R
    }

    /** Sun's elevation in degrees seen from the ground at (lat, lon). */
    fun sunElevation(lat: Double, lon: Double, ms: Long): Double = Sky.lookAngles(lat, lon, sun(ms))[0]

    /** Fraction of the Moon's disc lit, 0 (new) to 1 (full). */
    fun moonIllumination(ms: Long): Double {
        val s = sun(ms).norm()
        val m = moon(ms).norm()
        return (1 - (s dot m)) / 2
    }
}

/**
 * Sky view projection: a direction in the sky (azimuth from true north, elevation) to a
 * point on the camera preview, given the phone's rotation matrix (Android's
 * getRotationMatrixFromVector: device → east/north/up, row-major) and the compass
 * declination (magnetic north is [declinationDeg] east of true north).
 */
object SkyProjection {
    /** [x, y] in pixels, or null when the direction is behind the camera. */
    fun project(
        r: FloatArray, declinationDeg: Double, azDeg: Double, elDeg: Double,
        focalPx: Double, cx: Double, cy: Double,
    ): DoubleArray? {
        val az = Math.toRadians(azDeg - declinationDeg) // the sensor's north is magnetic
        val el = Math.toRadians(elDeg)
        val wx = sin(az) * cos(el)
        val wy = cos(az) * cos(el)
        val wz = sin(el)
        // device = Rᵀ · world
        val dx = r[0] * wx + r[3] * wy + r[6] * wz
        val dy = r[1] * wx + r[4] * wy + r[7] * wz
        val dz = r[2] * wx + r[5] * wy + r[8] * wz
        val forward = -dz // the back camera looks along the device's -Z
        if (forward < 0.05) return null
        return doubleArrayOf(cx + focalPx * dx / forward, cy - focalPx * dy / forward)
    }
}
