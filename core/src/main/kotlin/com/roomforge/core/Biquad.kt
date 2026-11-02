/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Direct form I biquad. The difference equation subtracts the feedback terms:
 * y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun processInPlace(buf: DoubleArray) {
        for (i in buf.indices) buf[i] = process(buf[i])
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    fun magnitude(freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val zRe = cos(w)
        val zIm = sin(w)
        val z2Re = cos(2.0 * w)
        val z2Im = sin(2.0 * w)
        val nRe = b0 + b1 * zRe + b2 * z2Re
        val nIm = -b1 * zIm - b2 * z2Im
        val dRe = 1.0 + a1 * zRe + a2 * z2Re
        val dIm = -a1 * zIm - a2 * z2Im
        return hypot(nRe, nIm) / hypot(dRe, dIm).coerceAtLeast(1e-30)
    }

    companion object {
        fun gain(k: Double) = Biquad(k, 0.0, 0.0, 0.0, 0.0)

        fun prewarp(freqHz: Double, sampleRate: Double): Double {
            val limited = freqHz.coerceIn(1.0, sampleRate * 0.45)
            return sampleRate / PI * tan(PI * limited / sampleRate)
        }

        /** Analog H(s) = s / (s + w) via the bilinear transform. */
        fun bilinearHighpass(poleHz: Double, sampleRate: Double): Biquad {
            val w = 2.0 * PI * prewarp(poleHz, sampleRate)
            val c = 2.0 * sampleRate
            val a0 = c + w
            return Biquad(c / a0, -c / a0, 0.0, (w - c) / a0, 0.0)
        }

        /** Analog H(s) = w^2 / (s + w)^2 via the bilinear transform. */
        fun bilinearDoubleRealPoleLowpass(poleHz: Double, sampleRate: Double): Biquad {
            val w = 2.0 * PI * prewarp(poleHz, sampleRate)
            val c = 2.0 * sampleRate
            val bigA = c + w
            val bigB = w - c
            val a0 = bigA * bigA
            val g = w * w
            return Biquad(g / a0, 2.0 * g / a0, g / a0, 2.0 * bigA * bigB / a0, bigB * bigB / a0)
        }

        fun rbjLowpass(freqHz: Double, sampleRate: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * freqHz.coerceAtMost(sampleRate * 0.45) / sampleRate
            val cosw = cos(w0)
            val sinw = sin(w0)
            val alpha = sinw / (2.0 * q)
            val b0 = (1.0 - cosw) / 2.0
            val b1 = 1.0 - cosw
            val b2 = (1.0 - cosw) / 2.0
            val a0 = 1.0 + alpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, -2.0 * cosw / a0, (1.0 - alpha) / a0)
        }

        fun rbjHighpass(freqHz: Double, sampleRate: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * freqHz.coerceAtLeast(1.0).coerceAtMost(sampleRate * 0.45) / sampleRate
            val cosw = cos(w0)
            val sinw = sin(w0)
            val alpha = sinw / (2.0 * q)
            val b0 = (1.0 + cosw) / 2.0
            val b1 = -(1.0 + cosw)
            val b2 = (1.0 + cosw) / 2.0
            val a0 = 1.0 + alpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, -2.0 * cosw / a0, (1.0 - alpha) / a0)
        }

        fun butterworth4Lowpass(freqHz: Double, sampleRate: Double): List<Biquad> {
            val q1 = 1.0 / (2.0 * sin(PI / 8.0))
            val q2 = 1.0 / (2.0 * sin(3.0 * PI / 8.0))
            val fc = freqHz.coerceAtMost(sampleRate * 0.45)
            return listOf(rbjLowpass(fc, sampleRate, q1), rbjLowpass(fc, sampleRate, q2))
        }

        fun butterworth4Highpass(freqHz: Double, sampleRate: Double): List<Biquad> {
            val q1 = 1.0 / (2.0 * sin(PI / 8.0))
            val q2 = 1.0 / (2.0 * sin(3.0 * PI / 8.0))
            val fc = freqHz.coerceIn(5.0, sampleRate * 0.45)
            return listOf(rbjHighpass(fc, sampleRate, q1), rbjHighpass(fc, sampleRate, q2))
        }
    }
}

class BiquadChain(private val stages: List<Biquad>) {
    fun process(x: Double): Double {
        var y = x
        for (s in stages) y = s.process(y)
        return y
    }

    fun processInPlace(buf: DoubleArray) {
        for (s in stages) s.processInPlace(buf)
    }

    fun reset() {
        stages.forEach { it.reset() }
    }

    fun magnitude(freqHz: Double, sampleRate: Double): Double {
        var m = 1.0
        for (s in stages) m *= s.magnitude(freqHz, sampleRate)
        return m
    }
}
