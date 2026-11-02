/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sample-accurate generator. Pink noise is Paul Kellet's economy IIR, which
 * falls about 3 dB/octave so a 1/3-octave RTA reads approximately flat.
 * White noise is equal energy per Hz, so octave bands rise about 3 dB/octave.
 * Warble follows the manual: rise from low to high over half the duration, then fall.
 */
class SignalGenerator(
    val sampleRate: Double,
    private val random: Random = Random(0x51A7)
) {
    @Volatile var waveform: Waveform = Waveform.SINE
    @Volatile var frequency: Double = 1000.0
    @Volatile var volume: Double = 0.2
    @Volatile var leftOn: Boolean = true
    @Volatile var rightOn: Boolean = true
    @Volatile var invertRight: Boolean = false
    @Volatile var sweepStartHz: Double = 100.0
    @Volatile var sweepEndHz: Double = 2000.0
    @Volatile var sweepDurationSec: Double = 10.0
    @Volatile var impulseIntervalSec: Double = 1.0

    @Volatile var currentHz: Double = 1000.0
        private set

    private var phase = 0.0
    private var sweepTime = 0.0
    private var impulseSample = 0
    private var polaritySample = 0
    private var lastWave = waveform
    private val pink = PinkFilter()
    private val polarity = PolarityPulse.template(sampleRate)

    fun renderStereo(frames: Int): Stereo {
        val left = DoubleArray(frames)
        val right = DoubleArray(frames)
        val vol = volume.coerceIn(0.0, 1.0)
        for (i in 0 until frames) {
            if (waveform != lastWave) {
                sweepTime = 0.0
                phase = 0.0
                impulseSample = 0
                polaritySample = 0
                lastWave = waveform
            }
            val s = nextSample() * vol
            var l = s
            var r = if (invertRight) -s else s
            if (!leftOn) l = 0.0
            if (!rightOn) r = 0.0
            left[i] = l
            right[i] = r
        }
        return Stereo(left, right)
    }

    fun renderMono(frames: Int): DoubleArray {
        val savedL = leftOn
        val savedR = rightOn
        val savedI = invertRight
        leftOn = true
        rightOn = true
        invertRight = false
        val rendered = renderStereo(frames).left
        leftOn = savedL
        rightOn = savedR
        invertRight = savedI
        return rendered
    }

    fun isSweeping(): Boolean = waveform == Waveform.SWEEP || waveform == Waveform.LOG_SWEEP || waveform == Waveform.WARBLE

    private fun nextSample(): Double {
        val hz = instantaneousHz()
        currentHz = hz
        return when (waveform) {
            Waveform.SINE -> sin(advancePhase(hz))
            Waveform.SQUARE -> if (advancePhase(hz) < PI) 1.0 else -1.0
            Waveform.TRIANGLE -> {
                val p = advancePhase(hz) / (2.0 * PI)
                if (p < 0.5) -1.0 + 4.0 * p else 3.0 - 4.0 * p
            }
            Waveform.RAMP -> -1.0 + 2.0 * (advancePhase(hz) / (2.0 * PI))
            Waveform.IMPULSE -> impulse()
            Waveform.SILENCE -> 0.0
            Waveform.WHITE -> random.nextDouble() * 2.0 - 1.0
            Waveform.PINK -> pink.next(random.nextDouble() * 2.0 - 1.0) * 0.18
            Waveform.SWEEP, Waveform.LOG_SWEEP, Waveform.WARBLE -> sin(advancePhase(hz))
            Waveform.POLARITY -> polaritySample()
        }
    }

    private fun instantaneousHz(): Double {
        val nyquist = sampleRate * 0.49
        if (waveform != Waveform.SWEEP && waveform != Waveform.LOG_SWEEP && waveform != Waveform.WARBLE) {
            return frequency.coerceIn(1.0, nyquist)
        }
        val dur = sweepDurationSec.coerceAtLeast(0.05)
        val frac = (sweepTime / dur) % 1.0
        sweepTime += 1.0 / sampleRate
        if (sweepTime >= dur) sweepTime -= dur
        val a = sweepStartHz.coerceIn(1.0, nyquist)
        val b = sweepEndHz.coerceIn(1.0, nyquist)
        return when (waveform) {
            Waveform.SWEEP -> a + (b - a) * frac
            Waveform.LOG_SWEEP -> a * (b / a).pow(frac)
            else -> {
                val tri = if (frac < 0.5) frac * 2.0 else (1.0 - frac) * 2.0
                a + (b - a) * tri
            }
        }
    }

    private fun advancePhase(hz: Double): Double {
        val current = phase
        phase += 2.0 * PI * hz / sampleRate
        while (phase >= 2.0 * PI) phase -= 2.0 * PI
        return current
    }

    private fun impulse(): Double {
        val interval = (impulseIntervalSec.coerceIn(0.05, 5.0) * sampleRate).toInt().coerceAtLeast(8)
        val pulseLen = (sampleRate * 0.001).toInt().coerceIn(8, interval / 2)
        val s = if (impulseSample < pulseLen) sin(PI * impulseSample / pulseLen) else 0.0
        impulseSample++
        if (impulseSample >= interval) impulseSample = 0
        return s
    }

    private fun polaritySample(): Double {
        val interval = (PolarityPulse.INTERVAL_SEC * sampleRate).toInt().coerceAtLeast(polarity.size + 1)
        val s = if (polaritySample < polarity.size) polarity[polaritySample] else 0.0
        polaritySample++
        if (polaritySample >= interval) polaritySample = 0
        return s
    }
}

/** Paul Kellet's economy pink-noise filter. */
class PinkFilter {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    fun next(white: Double): Double {
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return pink
    }
}

object PolarityPulse {
    const val INTERVAL_SEC = 0.50

    fun template(sampleRate: Double): DoubleArray {
        val n = (sampleRate * 0.0015).toInt().coerceIn(16, 256)
        return DoubleArray(n) { i -> sin(PI * i / (n - 1).coerceAtLeast(1)) }
    }

    fun detect(signal: DoubleArray, template: DoubleArray): Polarity {
        if (signal.size < template.size) return Polarity.UNKNOWN
        var energyT = 0.0
        for (v in template) energyT += v * v
        var best = 0.0
        val step = if (signal.size > 8000) 2 else 1
        val maxLag = signal.size - template.size
        var lag = 0
        while (lag <= maxLag) {
            var dot = 0.0
            var energy = 0.0
            for (i in template.indices) {
                val s = signal[lag + i]
                dot += s * template[i]
                energy += s * s
            }
            val norm = dot / kotlin.math.sqrt(energy * energyT + 1e-20)
            if (kotlin.math.abs(norm) > kotlin.math.abs(best)) best = norm
            lag += step
        }
        return when {
            best >= 0.28 -> Polarity.POSITIVE
            best <= -0.28 -> Polarity.NEGATIVE
            else -> Polarity.UNKNOWN
        }
    }
}
