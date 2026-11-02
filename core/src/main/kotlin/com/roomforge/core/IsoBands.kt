/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

object IsoBands {
    val octave1: List<Band> = fromNominal(
        doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    )

    /** Canonical ISO 1/3-octave nominal centres used by AudioTool .at / .targ files. */
    val octave3: List<Band> = fromNominal(
        doubleArrayOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
            200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
            2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
            16000.0, 20000.0
        )
    )

    val octave6: List<Band> = generated(6)
    val octave12: List<Band> = generated(12)

    fun forMode(mode: RtaMode): List<Band>? = when (mode) {
        RtaMode.OCTAVE_1 -> octave1
        RtaMode.OCTAVE_3 -> octave3
        RtaMode.OCTAVE_6, RtaMode.PEAK_STORE -> octave6
        RtaMode.OCTAVE_12 -> octave12
        else -> null
    }

    fun titleFor(mode: RtaMode): String? = when (mode) {
        RtaMode.OCTAVE_1 -> "1/1 Octave Data"
        RtaMode.OCTAVE_3 -> "1/3 Octave Data"
        RtaMode.OCTAVE_6, RtaMode.PEAK_STORE -> "1/6 Octave Data"
        RtaMode.OCTAVE_12 -> "1/12 Octave Data"
        else -> null
    }

    fun indexOf(bands: List<Band>, freqHz: Double): Int {
        if (freqHz < bands.first().lowHz || freqHz >= bands.last().highHz) return -1
        var lo = 0
        var hi = bands.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val b = bands[mid]
            when {
                freqHz < b.lowHz -> hi = mid - 1
                freqHz >= b.highHz -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun correctionAt(freqHz: Double, thirdBandDb: DoubleArray): Double {
        val bands = octave3
        if (thirdBandDb.size < bands.size) return 0.0
        val idx = indexOf(bands, freqHz)
        if (idx >= 0) return thirdBandDb[idx]
        return if (freqHz < bands.first().centerHz) thirdBandDb.first() else thirdBandDb.last()
    }

    fun integrate(freqHz: DoubleArray, meanSquare: DoubleArray, bands: List<Band>): DoubleArray {
        val out = DoubleArray(bands.size)
        for (k in freqHz.indices) {
            val f = freqHz[k]
            if (f <= 0.0) continue
            val idx = indexOf(bands, f)
            if (idx >= 0) out[idx] += meanSquare[k]
        }
        return out
    }

    private fun fromNominal(centers: DoubleArray): List<Band> {
        val n = centers.size
        val log = DoubleArray(n) { ln(centers[it]) }
        return List(n) { i ->
            val low = if (i == 0) exp(log[0] - (log[1] - log[0])) else exp(0.5 * (log[i - 1] + log[i]))
            val high = if (i == n - 1) exp(log[n - 1] + (log[n - 1] - log[n - 2])) else exp(0.5 * (log[i] + log[i + 1]))
            Band(centers[i], low, high)
        }
    }

    private fun generated(stepsPerOctave: Int): List<Band> {
        val raw = ArrayList<Double>()
        var k = -96
        while (k <= 96) {
            val f = 1000.0 * 2.0.pow(k.toDouble() / stepsPerOctave)
            if (f in 19.0..21000.0) raw.add(sig3(f))
            k++
        }
        val unique = ArrayList<Double>()
        for (f in raw) {
            if (unique.isEmpty() || abs(f - unique.last()) > 0.04) unique.add(f)
        }
        return fromNominal(unique.toDoubleArray())
    }

    private fun sig3(x: Double): Double {
        val exp = floor(log10(x))
        val scale = 10.0.pow(exp - 2.0)
        return (x / scale).roundToInt() * scale
    }
}
