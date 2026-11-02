/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

/**
 * Time-domain weighting applied to the raw microphone stream, so both the
 * RTA and the broadband SPL see the same filter. A and C follow IEC 61672
 * analog poles via the bilinear transform. The cinema X curve is the manual's
 * series combination: about +3 dB/octave below 60 Hz and -3 dB/octave above 2 kHz.
 */
class WeightingFilter(val type: Weighting, val sampleRate: Double) {
    private val chain: BiquadChain? = when (type) {
        Weighting.A -> BiquadChain(aWeighting(sampleRate))
        Weighting.C -> BiquadChain(cWeighting(sampleRate))
        else -> null
    }
    private val fir: OverlapSaveFilter? = if (type == Weighting.X) {
        OverlapSaveFilter(designXCurveFir(sampleRate))
    } else {
        null
    }

    fun process(input: DoubleArray): DoubleArray {
        if (input.isEmpty()) return DoubleArray(0)
        chain?.let {
            val out = input.copyOf()
            it.processInPlace(out)
            return out
        }
        fir?.let { return it.process(input) }
        return input.copyOf()
    }

    fun reset() {
        chain?.reset()
        fir?.reset()
    }

    companion object {
        const val F1 = 20.598997
        const val F2 = 107.65265
        const val F3 = 737.86223
        const val F4 = 12194.217

        fun aWeighting(sampleRate: Double): List<Biquad> {
            val sections = listOf(
                Biquad.bilinearHighpass(F1, sampleRate),
                Biquad.bilinearHighpass(F1, sampleRate),
                Biquad.bilinearHighpass(F2, sampleRate),
                Biquad.bilinearHighpass(F3, sampleRate),
                Biquad.bilinearDoubleRealPoleLowpass(F4, sampleRate)
            )
            return normalize(sections, sampleRate)
        }

        fun cWeighting(sampleRate: Double): List<Biquad> {
            val sections = listOf(
                Biquad.bilinearHighpass(F1, sampleRate),
                Biquad.bilinearHighpass(F1, sampleRate),
                Biquad.bilinearDoubleRealPoleLowpass(F4, sampleRate)
            )
            return normalize(sections, sampleRate)
        }

        fun xCurveDb(freqHz: Double): Double {
            val f = freqHz.coerceAtLeast(1.0)
            return when {
                f < 60.0 -> 3.0 * log2(f / 60.0)
                f > 2000.0 -> -3.0 * log2(f / 2000.0)
                else -> 0.0
            }
        }

        fun xCurveGain(freqHz: Double): Double = 10.0.pow(xCurveDb(freqHz) / 20.0)

        fun designXCurveFir(sampleRate: Double, taps: Int = 2048): DoubleArray {
            var n = 1
            while (n < taps) n = n shl 1
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            re[0] = xCurveGain(1.0)
            for (k in 1 until n / 2) {
                val mag = xCurveGain(k * sampleRate / n)
                re[k] = mag
                re[n - k] = mag
            }
            re[n / 2] = xCurveGain(sampleRate / 2.0)
            Fft.inverse(re, im)
            val h = DoubleArray(n)
            val half = n / 2
            for (i in 0 until n) h[i] = re[(i + half) % n]
            val edge = (0.12 * (n - 1) / 2.0).toInt().coerceAtLeast(1)
            for (i in 0 until edge) {
                val w = 0.5 * (1.0 - cos(PI * i / edge))
                h[i] *= w
                h[n - 1 - i] *= w
            }
            val gain = firGain(h, 1000.0, sampleRate).coerceAtLeast(1e-12)
            val scale = 1.0 / gain
            for (i in h.indices) h[i] *= scale
            return h
        }

        fun firGain(h: DoubleArray, freqHz: Double, sampleRate: Double): Double {
            val w = 2.0 * PI * freqHz / sampleRate
            var r = 0.0
            var im = 0.0
            for (i in h.indices) {
                r += h[i] * cos(w * i)
                im -= h[i] * sin(w * i)
            }
            return hypot(r, im)
        }

        private fun normalize(sections: List<Biquad>, sampleRate: Double): List<Biquad> {
            var mag = 1.0
            for (s in sections) mag *= s.magnitude(1000.0, sampleRate)
            return listOf(Biquad.gain(1.0 / mag.coerceAtLeast(1e-18))) + sections
        }
    }
}
