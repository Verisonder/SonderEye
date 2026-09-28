package com.verisonder.sondereye.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Satellites, computed on the phone from public orbital elements (CelesTrak TLEs).
 * SGP4 for near-Earth orbits (period under 225 min), following Vallado's reference
 * implementation with WGS-72 constants, the ones TLEs are fitted with.
 *
 * Deep-space orbits (period 225 min or more: GPS, geostationary…) use a simplified model
 * instead of SDP4: two-body motion with the J2 secular drift of the node, perigee and
 * mean anomaly. Near the element epoch it agrees with SDP4 to tens of kilometres, which
 * is invisible at those altitudes on a globe; it is for display, not for precise work.
 */
class Tle(
    val name: String,
    val norad: Int,
    val epochMs: Long,
    val bstar: Double,
    val inclDeg: Double,
    val raanDeg: Double,
    val ecc: Double,
    val argpDeg: Double,
    val maDeg: Double,
    /** Revolutions per day. */
    val meanMotion: Double,
) {
    val periodMin get() = 1440.0 / meanMotion

    companion object {
        class Result(val tles: List<Tle>, val deepSpace: Int, val unreadable: Int)

        /** Three-line format: name, line 1, line 2. Blank lines and stray text are skipped. */
        fun parseAll(text: String): Result {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = ArrayList<Tle>()
            var deep = 0
            var bad = 0
            var i = 0
            while (i < lines.size) {
                val a = lines[i]
                if (a.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ")) {
                    // Two-line set without a name line.
                    val t = runCatching { parse("", a, lines[i + 1]) }.getOrNull()
                    if (t == null) bad++ else { if (t.periodMin >= 225) deep++; out.add(t) }
                    i += 2
                } else if (i + 2 < lines.size && lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ")) {
                    val t = runCatching { parse(a.trim(), lines[i + 1], lines[i + 2]) }.getOrNull()
                    if (t == null) bad++ else { if (t.periodMin >= 225) deep++; out.add(t) }
                    i += 3
                } else {
                    i++
                }
            }
            return Result(out, deep, bad)
        }

        fun parse(name: String, l1: String, l2: String): Tle {
            require(l1.length >= 64 && l2.length >= 63) { "Short TLE line" }
            val norad = l1.substring(2, 7).trim().toInt()
            val yy = l1.substring(18, 20).trim().toInt()
            val day = l1.substring(20, 32).trim().toDouble()
            val year = if (yy < 57) 2000 + yy else 1900 + yy
            val epochMs = yearStartMs(year) + ((day - 1.0) * 86_400_000.0).toLong()
            return Tle(
                name = name.ifEmpty { "NORAD $norad" },
                norad = norad,
                epochMs = epochMs,
                bstar = expField(l1.substring(53, 61)),
                inclDeg = l2.substring(8, 16).trim().toDouble(),
                raanDeg = l2.substring(17, 25).trim().toDouble(),
                ecc = ("0." + l2.substring(26, 33).trim()).toDouble(),
                argpDeg = l2.substring(34, 42).trim().toDouble(),
                maDeg = l2.substring(43, 51).trim().toDouble(),
                meanMotion = l2.substring(52, 63).trim().toDouble(),
            )
        }

        /** " 66816-4" → 0.66816e-4. */
        private fun expField(f: String): Double {
            val s = f.trim()
            if (s.isEmpty()) return 0.0
            val sign = if (s.startsWith("-")) -1.0 else 1.0
            val body = s.trimStart('-', '+')
            val expSplit = body.indexOfAny(charArrayOf('-', '+'), 1)
            if (expSplit < 0) return sign * ("0.$body").toDouble()
            val mant = ("0." + body.substring(0, expSplit)).toDouble()
            val exp = body.substring(expSplit).toInt()
            return sign * mant * 10.0.pow(exp)
        }

        fun yearStartMs(year: Int): Long {
            // Days from 1970 to 1 January of [year], Gregorian.
            var days = 0L
            if (year >= 1970) for (y in 1970 until year) days += if (leap(y)) 366 else 365
            else for (y in year until 1970) days -= if (leap(y)) 366 else 365
            return days * 86_400_000L
        }

        private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    }
}

class Sgp4(val tle: Tle) {
    private val twoPi = 2 * PI
    private val x2o3 = 2.0 / 3.0

    // Initialised elements.
    private val no: Double
    private val ecco = tle.ecc
    private val inclo = Math.toRadians(tle.inclDeg)
    private val nodeo = Math.toRadians(tle.raanDeg)
    private val argpo = Math.toRadians(tle.argpDeg)
    private val mo = Math.toRadians(tle.maDeg)
    private val bstar = tle.bstar
    private var isimp = false
    private val con41: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val d2: Double
    private val d3: Double
    private val d4: Double
    private val delmo: Double
    private val eta: Double
    private val argpdot: Double
    private val omgcof: Double
    private val sinmao: Double
    private val t2cof: Double
    private val t3cof: Double
    private val t4cof: Double
    private val t5cof: Double
    private val xlcof: Double
    private val xmcof: Double
    private val mdot: Double
    private val nodecf: Double
    private val nodedot: Double
    private val aycof: Double

    init {
        val noKozai = tle.meanMotion * twoPi / 1440.0
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val eccsq = ecco * ecco
        val omeosq = 1 - eccsq
        val rteosq = sqrt(omeosq)

        // Un-Kozai the mean motion.
        val ak = (XKE / noKozai).pow(x2o3)
        val d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1 - del * del - del * (1.0 / 3.0 + 134 * del * del / 81))
        del = d1 / (adel * adel)
        no = noKozai / (1 + del)

        val ao = (XKE / no).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1 - 5 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1 - ecco)

        isimp = rp < (220.0 / RE + 1.0)
        var sfour = 78.0 / RE + 1.0
        var qzms24 = ((120.0 - 78.0) / RE).pow(4)
        val perige = (rp - 1.0) * RE
        if (perige < 156.0) {
            sfour = if (perige < 98.0) 20.0 else perige - 78.0
            qzms24 = ((120.0 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * no * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq)) +
            0.375 * J2 * tsi / psisq * con41 * (8 + 3 * etasq * (8 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2 * coef * tsi * J3OJ2 * no * sinio / ecco else 0.0
        x1mth2 = 1 - cosio2
        cc4 = 2 * no * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq) -
            J2 * tsi / (ao * psisq) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * cos(2 * argpo)))
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * no
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * no
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) +
            temp3 * (3 - 36 * cosio2 + 49 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -x2o3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1) > 1.5e-12) -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / (1 + cosio)
        else -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / 1.5e-12
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7 * cosio2 - 1

        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3
            d3 = (17 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1
            t3cof = d2 + 2 * cc1sq
            t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq))
            t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq))
        } else {
            d2 = 0.0; d3 = 0.0; d4 = 0.0; t3cof = 0.0; t4cof = 0.0; t5cof = 0.0
        }
    }

    val deepSpace = tle.periodMin >= 225

    /** TEME position (km) and velocity (km/s) [tsince] minutes after epoch, or null if decayed. */
    fun propagate(tsince: Double): DoubleArray? = if (deepSpace) kepler(tsince) else sgp4(tsince)

    /** Simplified deep-space model (see the class comment). */
    private fun kepler(t: Double): DoubleArray {
        val a = (XKE / no).pow(x2o3) // earth radii
        val p = a * (1 - ecco * ecco)
        val n = no
        val cosi = cos(inclo)
        val k = 1.5 * J2 / (p * p) * n
        val raan = nodeo - k * cosi * t
        val argp = argpo + k * (2 - 2.5 * (1 - cosi * cosi)) * t
        val m = mo + (n + k * sqrt(1 - ecco * ecco) * (1 - 1.5 * (1 - cosi * cosi))) * t
        var e = m
        repeat(12) { e -= (e - ecco * sin(e) - m) / (1 - ecco * cos(e)) }
        val cosE = cos(e); val sinE = sin(e)
        val r = a * (1 - ecco * cosE)
        val xp = a * (cosE - ecco)
        val yp = a * sqrt(1 - ecco * ecco) * sinE
        val rdot = sqrt(a) * XKE * ecco * sinE / r            // earth radii per minute
        val rfdot = sqrt(a * (1 - ecco * ecco)) * XKE / r
        val nu = atan2(yp, xp)
        val vx = rdot * cos(nu) - rfdot * sin(nu)
        val vy = rdot * sin(nu) + rfdot * cos(nu)
        val co = cos(raan); val so = sin(raan); val cw = cos(argp); val sw = sin(argp); val si = sin(inclo)
        val px = co * cw - so * sw * cosi; val py = so * cw + co * sw * cosi; val pz = sw * si
        val qx = -co * sw - so * cw * cosi; val qy = -so * sw + co * cw * cosi; val qz = cw * si
        val rr = r * RE
        val x = xp / r * rr; val y = yp / r * rr
        val vk = RE / 60.0
        return doubleArrayOf(
            x * px + y * qx, x * py + y * qy, x * pz + y * qz,
            (vx * px + vy * qx) * vk, (vx * py + vy * qy) * vk, (vx * pz + vy * qz) * vk,
        )
    }

    private fun sgp4(tsince: Double): DoubleArray? {
        val t = tsince
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2

        if (!isimp) {
            val delomg = omgcof * t
            val delm = xmcof * ((1 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }

        var em = ecco
        val am = (XKE / no).pow(x2o3) * tempa * tempa
        val nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001 || am < 0.95) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem = mod2pi(nodem)
        argpm = mod2pi(argpm)
        xlm = mod2pi(xlm)
        mm = mod2pi(xlm - argpm - nodem)

        val sinim = sin(inclo)
        val cosim = cos(inclo)

        // Long-period periodics.
        val axnl = em * cos(argpm)
        var temp = 1.0 / (am * (1 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        // Kepler's equation.
        val u = mod2pi(xl - nodem)
        var eo1 = u
        var tem5 = 9999.9
        var sineo1 = 0.0
        var coseo1 = 0.0
        var ktr = 1
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        // Short-period periodics.
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1 - el2)
        if (pl < 0) return null
        val rl = am * (1 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1 - el2)
        temp = esine / (1 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1 - 2 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp

        val mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosim * sin2u
        val xinc = inclo + 1.5 * temp2 * cosim * sinim * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE

        val sinsu = sin(su); val cossu = cos(su)
        val snod = sin(xnode); val cnod = cos(xnode)
        val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        if (mrt < 1.0) return null // below the surface: decayed
        val vk = RE * XKE / 60.0
        return doubleArrayOf(
            mrt * ux * RE, mrt * uy * RE, mrt * uz * RE,
            (mvt * ux + rvdot * vx) * vk, (mvt * uy + rvdot * vy) * vk, (mvt * uz + rvdot * vz) * vk,
        )
    }

    /** Earth-fixed position in metres at [ms], or null if decayed. */
    fun ecefAt(ms: Long): V3? {
        val r = propagate((ms - tle.epochMs) / 60_000.0) ?: return null
        val g = Sky.gmst(ms)
        val c = cos(g)
        val s = sin(g)
        return V3((c * r[0] + s * r[1]) * 1000, (-s * r[0] + c * r[1]) * 1000, r[2] * 1000)
    }

    /** Orbital speed in km/s at [ms]. */
    fun speedAt(ms: Long): Double? {
        val r = propagate((ms - tle.epochMs) / 60_000.0) ?: return null
        return sqrt(r[3] * r[3] + r[4] * r[4] + r[5] * r[5])
    }

    private fun mod2pi(x: Double): Double {
        val r = x - twoPi * floor(x / twoPi)
        return r
    }

    companion object {
        // WGS-72, as SGP4 requires.
        const val RE = 6378.135
        private const val MU = 398600.8
        val XKE = 60.0 / sqrt(RE * RE * RE / MU)
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val J3OJ2 = J3 / J2
    }
}

class Pass(
    val startMs: Long,
    val endMs: Long,
    val maxElevationDeg: Double,
    val startAzDeg: Double,
    val endAzDeg: Double,
    /** At its highest the satellite is sunlit while your sky is dark (sun below -6°): you can see it. */
    val visible: Boolean = false,
)

object Sky {
    /** Greenwich mean sidereal time, radians (IAU-82, as in Vallado's gstime). */
    fun gmst(ms: Long): Double {
        val jd = ms / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        var temp = -6.2e-6 * t * t * t + 0.093104 * t * t + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841
        temp = (Math.toRadians(temp) / 240.0) % (2 * PI)
        if (temp < 0) temp += 2 * PI
        return temp
    }

    /** [elevationDeg, azimuthDeg] of [target] seen from the ground at (lat, lon). */
    fun lookAngles(lat: Double, lon: Double, target: V3): DoubleArray {
        val obs = Geo.ecef(lat, lon)
        val up = obs.norm()
        var east = V3.Z cross up
        if (east.len() < 1e-9) east = V3(0.0, 1.0, 0.0)
        east = east.norm()
        val north = up cross east
        val d = (target - obs).norm()
        val el = Math.toDegrees(asin((d dot up).coerceIn(-1.0, 1.0)))
        var az = Math.toDegrees(atan2(d dot east, d dot north))
        if (az < 0) az += 360.0
        return doubleArrayOf(el, az)
    }

    /**
     * Passes above [minElDeg] over (lat, lon) in the next [hours], found on a 30 s scan
     * and refined to about a second.
     */
    fun passes(sat: Sgp4, lat: Double, lon: Double, fromMs: Long, hours: Double = 24.0, minElDeg: Double = 10.0): List<Pass> {
        fun el(ms: Long): Double = sat.ecefAt(ms)?.let { lookAngles(lat, lon, it)[0] } ?: -90.0
        fun edge(a: Long, b: Long, rising: Boolean): Long {
            var lo = a; var hi = b
            while (hi - lo > 1000) {
                val mid = (lo + hi) / 2
                val above = el(mid) >= minElDeg
                if (above == rising) hi = mid else lo = mid
            }
            return hi
        }
        val out = ArrayList<Pass>()
        val step = 30_000L
        val end = fromMs + (hours * 3_600_000).toLong()
        var t = fromMs
        var prevAbove = el(t) >= minElDeg
        var start = if (prevAbove) fromMs else -1L
        while (t < end) {
            val next = t + step
            val above = el(next) >= minElDeg
            if (above && !prevAbove) start = edge(t, next, true)
            if (!above && prevAbove && start >= 0) {
                val stop = edge(t, next, false)
                var maxEl = -90.0
                var maxAt = start
                var s = start
                while (s <= stop) {
                    val e = el(s)
                    if (e > maxEl) { maxEl = e; maxAt = s }
                    s += 10_000
                }
                val az = { ms: Long -> sat.ecefAt(ms)?.let { lookAngles(lat, lon, it)[1] } ?: 0.0 }
                val top = sat.ecefAt(maxAt)
                val visible = top != null && Astro.sunlit(top, maxAt) && Astro.sunElevation(lat, lon, maxAt) < -6.0
                out.add(Pass(start, stop, maxEl, az(start), az(stop), visible))
                start = -1L
            }
            prevAbove = above
            t = next
        }
        return out
    }

    fun compass(azDeg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((azDeg % 360) + 360 + 22.5) / 45).toInt() % 8]
    }

    /** CelesTrak groups. Each is a public URL, no key; CelesTrak asks for at most one download per 2 h. */
    fun celestrakUrl(group: String) = "https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=tle"
}
