/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object SpectrumMath {
    const val DISPLAY_BINS = 480
    const val DB_FLOOR = 1e-24

    fun powerToDb(meanSquare: Double, offsetDb: Double): Double {
        if (meanSquare <= DB_FLOOR) return offsetDb - 240.0
        return 10.0 * log10(meanSquare) + offsetDb
    }

    fun hann(n: Int): DoubleArray = DoubleArray(n) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / n))
    }

    data class WindowGains(val window: DoubleArray, val coherent: Double, val power: Double)

    fun windowGains(n: Int): WindowGains {
        val w = hann(n)
        var cg = 0.0
        var pg = 0.0
        for (v in w) {
            cg += v
            pg += v * v
        }
        return WindowGains(w, cg / n, pg / n)
    }

    /** One-sided mean-square per bin, Hann-power corrected, Parseval consistent. */
    fun binMeanSquare(windowedRe: DoubleArray, windowedIm: DoubleArray, powerGain: Double): DoubleArray {
        val n = windowedRe.size
        val re = windowedRe.copyOf()
        val im = windowedIm.copyOf()
        Fft.forward(re, im)
        val scale = 1.0 / (n.toDouble() * n.toDouble() * powerGain)
        val ms = DoubleArray(n / 2 + 1)
        ms[0] = (re[0] * re[0] + im[0] * im[0]) * scale
        for (k in 1 until n / 2) {
            ms[k] = 2.0 * (re[k] * re[k] + im[k] * im[k]) * scale
        }
        ms[n / 2] = (re[n / 2] * re[n / 2] + im[n / 2] * im[n / 2]) * scale
        return ms
    }

    /**
     * Tone-calibrated RMS^2 per bin. An on-bin Hann-windowed sinusoid of peak
     * amplitude A reads A^2/2 at the peak bin, matching a time-domain SPL.
     */
    fun toneMeanSquare(block: DoubleArray, window: DoubleArray, coherentGain: Double): DoubleArray {
        val n = block.size
        val re = DoubleArray(n) { block[it] * window[it] }
        val im = DoubleArray(n)
        Fft.forward(re, im)
        val out = DoubleArray(n / 2 + 1)
        for (k in 0..n / 2) {
            val mag = hypot(re[k], im[k])
            val amp = if (k == 0 || k == n / 2) mag / (n * coherentGain) else 2.0 * mag / (n * coherentGain)
            out[k] = amp * amp / 2.0
        }
        return out
    }

    fun binFrequencies(n: Int, sampleRate: Double): DoubleArray =
        DoubleArray(n / 2 + 1) { k -> k * sampleRate / n }

    /** Quadratic peak interpolation on a dB trace. Returns a fractional bin. */
    fun parabolicBin(db: DoubleArray, k: Int): Double {
        if (k <= 0 || k >= db.size - 1) return k.toDouble()
        val a = db[k - 1]
        val b = db[k]
        val c = db[k + 1]
        val denom = a - 2.0 * b + c
        if (abs(denom) < 1e-12) return k.toDouble()
        val delta = 0.5 * (a - c) / denom
        return k + delta.coerceIn(-0.5, 0.5)
    }

    fun decimateLogMax(
        freq: DoubleArray,
        db: DoubleArray,
        peak: DoubleArray,
        valley: DoubleArray,
        points: Int = DISPLAY_BINS,
        fMin: Double = 20.0,
        fMax: Double = 20000.0
    ): Curve {
        val outF = DoubleArray(points)
        val outDb = DoubleArray(points)
        val outP = DoubleArray(points)
        val outV = DoubleArray(points)
        val logMin = ln(fMin)
        val logMax = ln(fMax)
        var k = 1
        for (i in 0 until points) {
            val t0 = logMin + (logMax - logMin) * i / points
            val t1 = logMin + (logMax - logMin) * (i + 1) / points
            val a = exp(t0)
            val b = exp(t1)
            outF[i] = exp(0.5 * (t0 + t1))
            while (k < freq.size && freq[k] < a) k++
            var best = -300.0
            var pk = -300.0
            var vy = 300.0
            var any = false
            var j = k
            while (j < freq.size && freq[j] < b) {
                any = true
                if (db[j] > best) best = db[j]
                if (peak[j] > pk) pk = peak[j]
                if (valley[j] < vy) vy = valley[j]
                j++
            }
            outDb[i] = if (any) best else -240.0
            outP[i] = if (any) pk else -240.0
            outV[i] = if (any) vy else -240.0
        }
        return Curve(outF, outDb, outP, outV)
    }

    fun bandCurve(bands: List<Band>, db: DoubleArray, peak: DoubleArray, valley: DoubleArray): Curve {
        val n = bands.size
        return Curve(
            DoubleArray(n) { bands[it].centerHz },
            db.copyOf(n),
            peak.copyOf(n),
            valley.copyOf(n)
        )
    }
}

class ExpPowerSmoother(size: Int) {
    private val y = DoubleArray(size)

    fun update(input: DoubleArray, dt: Double, tau: Double): DoubleArray {
        val decay = exp(-dt / tau.coerceAtLeast(1e-4))
        val out = DoubleArray(input.size)
        val n = min(input.size, y.size)
        for (i in 0 until n) {
            val v = y[i] * decay + input[i] * (1.0 - decay)
            y[i] = v
            out[i] = v
        }
        return out
    }

    fun reset() {
        y.fill(0.0)
    }
}

class DbAverager(private var size: Int) {
    private var sum = DoubleArray(size)
    var count: Int = 0
        private set

    fun ensure(n: Int) {
        if (n != size) {
            size = n
            sum = DoubleArray(n)
            count = 0
        }
    }

    fun add(db: DoubleArray) {
        if (count == 0 && sum.size != db.size) ensure(db.size)
        count++
        val n = min(db.size, sum.size)
        for (i in 0 until n) sum[i] += db[i]
    }

    fun mean(): DoubleArray {
        val c = count.coerceAtLeast(1)
        return DoubleArray(sum.size) { sum[it] / c }
    }

    fun reset() {
        count = 0
        sum.fill(0.0)
    }
}

object Music {
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun noteName(freqHz: Double): String {
        if (freqHz <= 0.0) return ""
        val midi = (69.0 + 12.0 * log2(freqHz / 440.0)).roundToInt()
        val name = names.floorModIndex(midi)
        val octave = midi / 12 - 1
        return "$name$octave"
    }

    private fun Array<String>.floorModIndex(midi: Int): String = this[((midi % 12) + 12) % 12]
}

fun freqAtCursor(freq: Double, curve: Curve): Double {
    if (curve.size == 0) return Double.NaN
    if (curve.size == 1) return curve.db[0]
    if (freq <= curve.freqHz.first()) return curve.db.first()
    if (freq >= curve.freqHz.last()) return curve.db.last()
    var lo = 0
    var hi = curve.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (curve.freqHz[mid] <= freq) lo = mid else hi = mid
    }
    val f0 = curve.freqHz[lo]
    val f1 = curve.freqHz[hi]
    val t = if (f1 == f0) 0.0 else (ln(freq) - ln(f0)) / (ln(f1) - ln(f0))
    return curve.db[lo] + (curve.db[hi] - curve.db[lo]) * t.coerceIn(0.0, 1.0)
}

fun nearestBandDb(freq: Double, bands: List<Band>, db: DoubleArray): Double {
    val idx = IsoBands.indexOf(bands, freq)
    if (idx >= 0 && idx < db.size) return db[idx]
    if (bands.isEmpty() || db.isEmpty()) return Double.NaN
    var best = 0
    var bestD = Double.MAX_VALUE
    for (i in bands.indices) {
        val d = abs(log2(bands[i].centerHz.coerceAtLeast(1.0)) - log2(freq.coerceAtLeast(1.0)))
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return db[best]
}
