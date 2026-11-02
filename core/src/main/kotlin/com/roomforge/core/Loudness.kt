/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/**
 * Equal-loudness conversion from ISO 226:2003, combined with the HVI-style
 * formula quoted by the AudioTool manual:
 * Sone = 0.7 * (loudness at 1 kHz) + 0.3 * (sum of loudness, all eight bands).
 * This is an estimate, not a certified loudness meter. Flat weighting is the
 * appropriate input; A or C weighting under-reads low frequencies a second time.
 */
object Sones {
    private val freqs = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0,
        400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0,
        5000.0, 6300.0, 8000.0, 10000.0, 12500.0
    )
    private val alpha = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301,
        0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242,
        0.242, 0.245, 0.254, 0.271, 0.301
    )
    private val lu = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1,
        -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1
    )
    private val tf = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2,
        4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3
    )

    val bandsHz = intArrayOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)

    fun splOfPhon(phon: Double, freqHz: Double): Double {
        val i = nearest(freqHz)
        val af = alpha[i]
        val luF = lu[i]
        val tfF = tf[i]
        val ln = phon.coerceIn(0.0, 90.0)
        val afTerm = 4.47e-3 * (10.0.pow(0.025 * ln) - 1.15) +
            (0.4 * 10.0.pow((tfF + luF) / 10.0 - 9.0)).pow(af)
        return (10.0 / af) * log10(afTerm.coerceAtLeast(1e-30)) - luF + 94.0
    }

    fun phonOfSpl(splDb: Double, freqHz: Double): Double {
        val i = nearest(freqHz)
        if (splDb <= tf[i]) return 0.0
        var lo = 0.0
        var hi = 90.0
        if (splDb >= splOfPhon(90.0, freqHz)) return 90.0
        repeat(28) {
            val mid = 0.5 * (lo + hi)
            if (splOfPhon(mid, freqHz) < splDb) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    fun bandSone(splDb: Double, freqHz: Double): Double {
        val phon = phonOfSpl(splDb, freqHz)
        if (phon <= 0.0) return 0.0
        return 2.0.pow((phon - 40.0) / 10.0)
    }

    fun estimate(octaveDb: Map<Int, Double>): Double {
        var sum = 0.0
        var at1k = 0.0
        for (f in bandsHz) {
            val s = bandSone(octaveDb[f] ?: -80.0, f.toDouble())
            sum += s
            if (f == 1000) at1k = s
        }
        return 0.7 * at1k + 0.3 * sum
    }

    fun fromOctaveCurve(curve: Curve): Double {
        val map = HashMap<Int, Double>()
        for (f in bandsHz) {
            var best = 0
            var bestD = Double.MAX_VALUE
            for (i in curve.freqHz.indices) {
                val d = abs(curve.freqHz[i] - f)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            if (curve.db.isNotEmpty()) map[f] = curve.db[best]
        }
        return estimate(map)
    }

    private fun nearest(freqHz: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in freqs.indices) {
            val d = abs(freqs[i] - freqHz)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }
}

/** NC (ASHRAE) and NR curves, drawn and rated from 1/1-octave levels. */
object NoiseCurves {
    val ncNumbers = intArrayOf(15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65)
    val ncFreq = doubleArrayOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)

    private val ncTable = arrayOf(
        doubleArrayOf(47.0, 36.0, 29.0, 22.0, 17.0, 14.0, 12.0, 11.0),
        doubleArrayOf(51.0, 40.0, 33.0, 26.0, 22.0, 19.0, 17.0, 16.0),
        doubleArrayOf(54.0, 44.0, 37.0, 31.0, 27.0, 24.0, 22.0, 21.0),
        doubleArrayOf(57.0, 48.0, 41.0, 35.0, 31.0, 29.0, 28.0, 27.0),
        doubleArrayOf(60.0, 52.0, 45.0, 40.0, 36.0, 34.0, 33.0, 32.0),
        doubleArrayOf(64.0, 56.0, 50.0, 45.0, 41.0, 39.0, 38.0, 37.0),
        doubleArrayOf(67.0, 60.0, 54.0, 49.0, 46.0, 44.0, 43.0, 42.0),
        doubleArrayOf(71.0, 64.0, 58.0, 54.0, 51.0, 49.0, 48.0, 47.0),
        doubleArrayOf(74.0, 67.0, 62.0, 58.0, 56.0, 54.0, 53.0, 52.0),
        doubleArrayOf(77.0, 71.0, 67.0, 63.0, 61.0, 59.0, 58.0, 57.0),
        doubleArrayOf(80.0, 75.0, 71.0, 68.0, 66.0, 64.0, 63.0, 62.0)
    )

    private val nrFreq = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
    private val nrA = doubleArrayOf(55.4, 35.5, 22.0, 12.0, 4.8, 0.0, -3.5, -6.1, -8.0)
    private val nrB = doubleArrayOf(0.681, 0.790, 0.870, 0.930, 0.974, 1.0, 1.015, 1.025, 1.030)

    fun ncLevels(nc: Int): DoubleArray {
        val idx = ncNumbers.indexOf(nc)
        return if (idx >= 0) ncTable[idx].copyOf() else DoubleArray(ncFreq.size)
    }

    /** Smallest standard NC curve not exceeded by any of 63 Hz–8 kHz. */
    fun ncRating(octaveFreq: DoubleArray, octaveDb: DoubleArray): String {
        val levels = DoubleArray(ncFreq.size) { band ->
            levelNear(ncFreq[band], octaveFreq, octaveDb)
        }
        if (levels.any { it.isNaN() }) return "NC ?"
        for (row in ncNumbers.indices) {
            var ok = true
            for (b in levels.indices) {
                if (levels[b] > ncTable[row][b] + 0.05) {
                    ok = false
                    break
                }
            }
            if (ok) return "NC-${ncNumbers[row]}"
        }
        return "NC>65"
    }

    fun nrLevel(nr: Int, freqHz: Double): Double {
        val i = nrFreq.indices.minBy { abs(nrFreq[it] - freqHz) }
        return nrA[i] + nrB[i] * nr
    }

    fun nrRating(octaveFreq: DoubleArray, octaveDb: DoubleArray): String {
        var maxN = 0.0
        for (i in nrFreq.indices) {
            val l = levelNear(nrFreq[i], octaveFreq, octaveDb)
            if (l.isNaN()) continue
            val n = (l - nrA[i]) / nrB[i]
            if (n > maxN) maxN = n
        }
        // A level that sits on a curve should rate as that curve, not the next one.
        val rounded = ceil(maxN - 0.05).toInt().coerceAtLeast(0)
        return "NR-$rounded"
    }

    private fun levelNear(freq: Double, octaveFreq: DoubleArray, octaveDb: DoubleArray): Double {
        if (octaveFreq.isEmpty()) return Double.NaN
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in octaveFreq.indices) {
            val d = abs(octaveFreq[i] - freq)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (bestD > freq * 0.3) Double.NaN else octaveDb[best]
    }
}
