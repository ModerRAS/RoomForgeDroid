/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DecayMetrics(val earlySec: Double, val t15to20Sec: Double, val valid: Boolean)

data class Rt60Report(
    val wideband: DecayMetrics,
    val bands: List<Pair<Double, DecayMetrics>>,
    val curveDb: DoubleArray,
    val curveDtSec: Double
)

enum class Rt60Phase { IDLE, WAITING, RECORDING, READY }

/**
 * Schroeder backward integration. [power] is mean-square per frame of length [dtSec].
 * RT Early is the time from the peak to -10 dB, multiplied by 6.
 * T15-20 is the time from -5 dB to -20 dB, multiplied by 4.
 */
object Schroeder {
    fun integrate(power: DoubleArray, dtSec: Double): Pair<DoubleArray, DecayMetrics> {
        if (power.size < 8) {
            return DoubleArray(0) to DecayMetrics(Double.NaN, Double.NaN, false)
        }
        var peak = 0
        for (i in power.indices) if (power[i] > power[peak]) peak = i
        val sliced = power.copyOfRange(peak, power.size)
        val noise = estimateNoise(sliced)
        val energy = DoubleArray(sliced.size)
        var acc = 0.0
        for (i in sliced.lastIndex downTo 0) {
            acc += max(0.0, sliced[i] - noise)
            energy[i] = acc
        }
        val e0 = energy[0].coerceAtLeast(1e-30)
        val db = DoubleArray(energy.size) { i -> 10.0 * log10((energy[i] / e0).coerceAtLeast(1e-20)) }
        val t10 = timeAt(db, dtSec, -10.0)
        val t5 = timeAt(db, dtSec, -5.0)
        val t20 = timeAt(db, dtSec, -20.0)
        val early = if (t10 != null) t10 * 6.0 else Double.NaN
        val t1520 = if (t5 != null && t20 != null && t20 > t5) (t20 - t5) * 4.0 else Double.NaN
        val dynamic = if (db.isEmpty()) 0.0 else -db.last()
        val valid = t10 != null && dynamic >= 10.0 && early.isFinite()
        return db to DecayMetrics(early, t1520, valid && t1520.isFinite())
    }

    private fun estimateNoise(power: DoubleArray): Double {
        val n = power.size
        val a = (n * 0.55).toInt()
        val b = (n * 0.75).toInt().coerceAtMost(n)
        val c = (n * 0.85).toInt()
        if (b <= a || n <= c) return 0.0
        val mid = median(power, a, b)
        val tail = median(power, c, n)
        return if (mid > tail * 4.0) 0.0 else tail
    }

    private fun median(values: DoubleArray, from: Int, until: Int): Double {
        val copy = values.copyOfRange(from, until).apply { sort() }
        if (copy.isEmpty()) return 0.0
        return copy[copy.size / 2]
    }

    private fun timeAt(db: DoubleArray, dt: Double, level: Double): Double? {
        for (i in db.indices) if (db[i] <= level) return i * dt
        return null
    }
}

/**
 * Wideband plus ISO octave-band RT60. The octave bank is a 4th-order Butterworth
 * high-pass/low-pass pair on each ISO centre. A clap arms the recorder when the
 * generator is off; [trigger] is the generator-stop path.
 */
class Rt60Recorder(val sampleRate: Double) {
    var phase: Rt60Phase = Rt60Phase.IDLE
        private set
    var report: Rt60Report? = null
        private set
    var liveDb: Double = -120.0
        private set

    private val centers = IsoBands.octave1
    private val banks: List<BiquadChain> = centers.map { band ->
        val stages = ArrayList<Biquad>()
        stages += Biquad.butterworth4Highpass(band.lowHz, sampleRate)
        stages += Biquad.butterworth4Lowpass(band.highHz, sampleRate)
        BiquadChain(stages)
    }
    private val wideHp = Biquad.bilinearHighpass(30.0, sampleRate)
    private val frameLen = max(1, (sampleRate / 200.0).toInt())
    private val recordFrames = (8.0 * sampleRate / frameLen).toInt()
    private val preRollFrames = (0.30 * sampleRate / frameLen).toInt()
    private val wide = ArrayDeque<Double>()
    private val bandPower = List(centers.size) { ArrayDeque<Double>() }
    private var frameFill = 0
    private var wideAcc = 0.0
    private val bandAcc = DoubleArray(centers.size)
    private var fast = 1e-8
    private var slow = 1e-8
    private var fastAtFrameStart = 1e-8
    private var quietFrames = 0
    private val aFast = 1.0 - exp(-1.0 / (sampleRate * 0.010))
    private val aSlow = 1.0 - exp(-1.0 / (sampleRate * 0.40))

    fun armClap() {
        if (phase == Rt60Phase.RECORDING) return
        clearRecording()
        phase = Rt60Phase.WAITING
        report = null
    }

    fun trigger() {
        // Keep the pre-roll that is already in the deques and start a fixed capture.
        phase = Rt60Phase.RECORDING
        report = null
        trimToPreRoll()
    }

    fun reset() {
        phase = Rt60Phase.IDLE
        report = null
        clearRecording()
        banks.forEach { it.reset() }
        wideHp.reset()
        fast = 1e-8
        slow = 1e-8
        fastAtFrameStart = 1e-8
        quietFrames = 0
    }

    fun push(samples: DoubleArray) {
        for (s in samples) {
            if (frameFill == 0) fastAtFrameStart = fast
            val p = s * s
            fast += aFast * (p - fast)
            slow += aSlow * (p - slow)
            val wideS = wideHp.process(s)
            wideAcc += wideS * wideS
            for (b in banks.indices) {
                val y = banks[b].process(s)
                bandAcc[b] += y * y
            }
            frameFill++
            if (frameFill >= frameLen) {
                pushFrame(wideAcc / frameLen, DoubleArray(bandAcc.size) { bandAcc[it] / frameLen })
                wideAcc = 0.0
                bandAcc.fill(0.0)
                frameFill = 0
            }
        }
        liveDb = 10.0 * log10(fast.coerceAtLeast(1e-20))
    }

    private fun pushFrame(wideMs: Double, bandsMs: DoubleArray) {
        if (phase == Rt60Phase.WAITING || phase == Rt60Phase.RECORDING) {
            wide.addLast(wideMs)
            for (b in bandsMs.indices) bandPower[b].addLast(bandsMs[b])
            while (phase == Rt60Phase.WAITING && wide.size > preRollFrames) {
                wide.removeFirst()
                bandPower.forEach { it.removeFirst() }
            }
        }
        val rising = fast > fastAtFrameStart * 1.8
        if (phase == Rt60Phase.WAITING && rising && fast > slow * 4.0 && fast > 1e-4) {
            phase = Rt60Phase.RECORDING
        }
        if (phase == Rt60Phase.RECORDING) {
            val peak = wide.maxOrNull() ?: wideMs
            val longEnough = wide.size > (0.45 * sampleRate / frameLen)
            if (longEnough && wideMs < peak * 1e-3) quietFrames++ else quietFrames = 0
            val settled = quietFrames > (0.20 * sampleRate / frameLen)
            if (wide.size >= recordFrames || (longEnough && settled)) finish()
        }
    }

    private fun finish() {
        val dt = frameLen / sampleRate
        val wideArr = wide.toDoubleArray()
        val (curve, wideMetrics) = Schroeder.integrate(wideArr, dt)
        val bandResults = centers.mapIndexed { i, band ->
            val (_, metrics) = Schroeder.integrate(bandPower[i].toDoubleArray(), dt)
            band.centerHz to metrics
        }
        // A result with a usable early decay is shown even if T15-20 could not be fit.
        val shownWide = wideMetrics.copy(valid = wideMetrics.earlySec.isFinite())
        report = Rt60Report(shownWide, bandResults, curve, dt)
        phase = Rt60Phase.READY
        clearRecording()
    }

    private fun trimToPreRoll() {
        while (wide.size > preRollFrames) wide.removeFirst()
        bandPower.forEach { deque -> while (deque.size > preRollFrames) deque.removeFirst() }
    }

    private fun clearRecording() {
        wide.clear()
        bandPower.forEach { it.clear() }
    }
}

/** Synthetic exponential energy decay, useful for tests and for understanding the fit. */
fun syntheticDecayPower(seconds: Double, dt: Double, rt60: Double, floor: Double = 0.0): DoubleArray {
    val n = (seconds / dt).toInt()
    val tau = rt60 / ln(1.0e6)
    return DoubleArray(n) { i ->
        val t = i * dt
        exp(-t / tau) + floor
    }
}

fun rms(samples: DoubleArray, from: Int = 0, until: Int = samples.size): Double {
    if (until <= from) return 0.0
    var s = 0.0
    for (i in from until until) s += samples[i] * samples[i]
    return sqrt(s / (until - from))
}
