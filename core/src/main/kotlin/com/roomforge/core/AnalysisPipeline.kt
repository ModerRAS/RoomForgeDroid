/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns weighted PCM into RTA, SPL, Leq, chart and peak-store state.
 * Octave bands sum Parseval power. The full-resolution line is normalised so an
 * on-bin Hann sine reads the same level as the time-domain SPL. Averaging is an
 * arithmetic mean of those dB values. Fast / Medium / Slow / Impulse are
 * exponential filters on power, with the documented time constants.
 */
class AnalysisPipeline(
    sampleRate: Double = 44100.0,
    fftSize: Int = 8192
) {
    private val lock = Any()

    var sampleRate: Double = sampleRate
        private set
    var fftSize: Int = fftSize
        private set
    var weighting: Weighting = Weighting.FLAT
        private set
    var timeConstant: TimeConstant = TimeConstant.MEDIUM
    var calibration: Calibration = Calibration()
    var averagingEnabled: Boolean = false
    var peaksEnabled: Boolean = true
    var valleysEnabled: Boolean = true
    var capturePeakStore: Boolean = false
    var leqDurationSec: Double = 60.0
    var paused: Boolean = false

    val hop: Int
        get() {
            val maxHop = (fftSize / 2).coerceAtLeast(1)
            val target = (sampleRate * 0.023).toInt().coerceAtLeast(32)
            return target.coerceAtMost(maxHop).coerceAtLeast(1)
        }

    private var filter = WeightingFilter(Weighting.FLAT, sampleRate)
    private var gains = SpectrumMath.windowGains(fftSize)
    private var buf = DoubleArray(fftSize * 2)
    private var len = 0

    private val scope = DoubleArray(2048)
    private var scopeWrite = 0
    private var scopeCount = 0

    private var fullSmooth = ExpPowerSmoother(fftSize / 2 + 1)
    private val oct1Smooth = ExpPowerSmoother(IsoBands.octave1.size)
    private val oct3Smooth = ExpPowerSmoother(IsoBands.octave3.size)
    private val oct6Smooth = ExpPowerSmoother(IsoBands.octave6.size)
    private val oct12Smooth = ExpPowerSmoother(IsoBands.octave12.size)
    private var splPower = Double.NaN

    private var fullAvg = DbAverager(fftSize / 2 + 1)
    private var oct1Avg = DbAverager(IsoBands.octave1.size)
    private var oct3Avg = DbAverager(IsoBands.octave3.size)
    private var oct6Avg = DbAverager(IsoBands.octave6.size)
    private var oct12Avg = DbAverager(IsoBands.octave12.size)

    private var binPeak = DoubleArray(fftSize / 2 + 1) { Double.NaN }
    private var binValley = DoubleArray(fftSize / 2 + 1) { Double.NaN }
    private var p1 = nan(IsoBands.octave1.size)
    private var v1 = nan(IsoBands.octave1.size)
    private var p3 = nan(IsoBands.octave3.size)
    private var v3 = nan(IsoBands.octave3.size)
    private var p6 = nan(IsoBands.octave6.size)
    private var v6 = nan(IsoBands.octave6.size)
    private var p12 = nan(IsoBands.octave12.size)
    private var v12 = nan(IsoBands.octave12.size)
    private var peakStore = nan(IsoBands.octave6.size)

    private var binFreq = DoubleArray(0)
    private var binDb = DoubleArray(0)
    private var binPeakOut = DoubleArray(0)
    private var binValleyOut = DoubleArray(0)

    private var leqMs = 0.0
    private var leqN = 0L
    private var leqBand = DoubleArray(IsoBands.octave1.size)
    private var leqStartMs = System.currentTimeMillis()
    private val leqHistory = ArrayList<LeqRecord>()

    private val chartTime = DoubleArray(10_000)
    private val chartSpl = DoubleArray(10_000)
    private val chartPeak = DoubleArray(10_000)
    private val chartSweep = DoubleArray(10_000)
    private var chartStart = 0
    private var chartSize = 0
    private var elapsed = 0.0

    private var generation = 0L
    private var last: DisplayFrame? = null
    private var toneHz = Double.NaN

    fun setSampleRate(rate: Double) = synchronized(lock) {
        sampleRate = rate
        filter = WeightingFilter(weighting, rate)
        resetSpectrumState()
        resetLeqLocked()
    }

    fun setFftSize(size: Int) = synchronized(lock) {
        require(size >= 256 && size and (size - 1) == 0)
        fftSize = size
        gains = SpectrumMath.windowGains(size)
        fullSmooth = ExpPowerSmoother(size / 2 + 1)
        fullAvg = DbAverager(size / 2 + 1)
        binPeak = nan(size / 2 + 1)
        binValley = nan(size / 2 + 1)
        buf = DoubleArray(size * 2)
        len = 0
        binFreq = DoubleArray(0)
    }

    fun setWeighting(value: Weighting) = synchronized(lock) {
        weighting = value
        filter = WeightingFilter(value, sampleRate)
        len = 0
        splPower = Double.NaN
        resetLeqLocked()
    }

    fun replaceCalibration(value: Calibration) = synchronized(lock) {
        calibration = value
        resetLeqLocked()
        resetHolds()
        resetAveragers()
    }

    /** Live trim while dragging calibration discs. Does not restart Leq. */
    fun setCalibrationLive(value: Calibration) = synchronized(lock) {
        calibration = value
    }

    fun setAveraging(enabled: Boolean) = synchronized(lock) {
        averagingEnabled = enabled
        resetAveragers()
    }

    /** Main-thread control updates. Reads of these fields happen under the same lock. */
    fun applyRuntime(
        paused: Boolean,
        timeConstant: TimeConstant,
        averagingEnabled: Boolean,
        peaksEnabled: Boolean,
        valleysEnabled: Boolean,
        capturePeakStore: Boolean,
        leqDurationSec: Double
    ) = synchronized(lock) {
        if (averagingEnabled != this.averagingEnabled) resetAveragers()
        if (peaksEnabled && !this.peaksEnabled) {
            binPeak.fill(Double.NaN)
            p1.fill(Double.NaN); p3.fill(Double.NaN); p6.fill(Double.NaN); p12.fill(Double.NaN)
        }
        if (valleysEnabled && !this.valleysEnabled) {
            binValley.fill(Double.NaN)
            v1.fill(Double.NaN); v3.fill(Double.NaN); v6.fill(Double.NaN); v12.fill(Double.NaN)
        }
        this.paused = paused
        this.timeConstant = timeConstant
        this.averagingEnabled = averagingEnabled
        this.peaksEnabled = peaksEnabled
        this.valleysEnabled = valleysEnabled
        this.capturePeakStore = capturePeakStore
        this.leqDurationSec = leqDurationSec
    }

    fun enablePeaks(enabled: Boolean) = synchronized(lock) {
        peaksEnabled = enabled
        if (enabled) {
            binPeak.fill(Double.NaN)
            p1.fill(Double.NaN); p3.fill(Double.NaN); p6.fill(Double.NaN); p12.fill(Double.NaN)
        }
    }

    fun enableValleys(enabled: Boolean) = synchronized(lock) {
        valleysEnabled = enabled
        if (enabled) {
            binValley.fill(Double.NaN)
            v1.fill(Double.NaN); v3.fill(Double.NaN); v6.fill(Double.NaN); v12.fill(Double.NaN)
        }
    }

    fun resetPeakStore() = synchronized(lock) {
        peakStore.fill(Double.NaN)
    }

    fun resetLeq() = synchronized(lock) { resetLeqLocked() }

    private fun resetLeqLocked() {
        leqMs = 0.0
        leqN = 0L
        leqBand.fill(0.0)
        leqStartMs = System.currentTimeMillis()
    }

    fun clearLeqHistory() = synchronized(lock) { leqHistory.clear() }

    fun allLeq(): List<LeqRecord> = synchronized(lock) { leqHistory.toList() }

    fun allChart(): List<ChartSample> = synchronized(lock) {
        List(chartSize) { i ->
            val idx = (chartStart + i) % chartTime.size
            ChartSample(chartTime[idx], chartSpl[idx], chartPeak[idx], chartSweep[idx])
        }
    }

    fun push(raw: DoubleArray, externalToneHz: Double = Double.NaN): DisplayFrame? = synchronized(lock) {
        toneHz = externalToneHz
        appendScope(raw)
        if (paused) return@synchronized withScope(last)
        val weighted = filter.process(raw)
        if (weighted.isEmpty()) return@synchronized withScope(last)
        ensure(len + weighted.size)
        System.arraycopy(weighted, 0, buf, len, weighted.size)
        len += weighted.size
        var frame: DisplayFrame? = null
        while (len >= fftSize) {
            frame = compute()
            val remain = len - hop
            if (remain > 0) System.arraycopy(buf, hop, buf, 0, remain)
            len = remain.coerceAtLeast(0)
        }
        withScope(frame ?: last)
    }

    fun dump(mode: RtaMode): SpectrumDump = synchronized(lock) {
        val frame = last
        val bands = IsoBands.forMode(mode)
        val curve = when (mode) {
            RtaMode.OCTAVE_1 -> frame?.oct1
            RtaMode.OCTAVE_3 -> frame?.oct3
            RtaMode.OCTAVE_6 -> frame?.oct6
            RtaMode.OCTAVE_12 -> frame?.oct12
            RtaMode.PEAK_STORE -> frame?.peakStore
            else -> null
        }
        val useHolds = mode != RtaMode.PEAK_STORE
        SpectrumDump(
            sampleRate = sampleRate,
            fftSize = fftSize,
            weighting = weighting,
            globalCalibrationDb = calibration.broadbandOffset(),
            binFreqHz = binFreq.copyOf(),
            binMagnitude = DoubleArray(binDb.size) { i ->
                MeasurementFiles.magnitudeForFormula(binDb[i], calibration.broadbandOffset())
            },
            binDb = binDb.copyOf(),
            binPeakDb = binPeakOut.copyOf(),
            binValleyDb = binValleyOut.copyOf(),
            bandTitle = IsoBands.titleFor(mode),
            bandFreqHz = curve?.freqHz?.copyOf(),
            bandDb = curve?.db?.copyOf(),
            bandValleyDb = if (useHolds) curve?.valleyDb?.copyOf() else curve?.db?.copyOf(),
            bandPeakDb = if (mode == RtaMode.PEAK_STORE) curve?.db?.copyOf() else curve?.peakDb?.copyOf()
        )
    }

    private fun compute(): DisplayFrame {
        val started = System.nanoTime()
        val n = fftSize
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val window = gains.window
        for (i in 0 until n) re[i] = buf[i] * window[i]
        Fft.forward(re, im)
        val half = n / 2
        val ms = DoubleArray(half + 1)
        val tone = DoubleArray(half + 1)
        val scale = 1.0 / (n.toDouble() * n.toDouble() * gains.power)
        val cg = gains.coherent.coerceAtLeast(1e-12)
        for (k in 0..half) {
            val mag2 = re[k] * re[k] + im[k] * im[k]
            ms[k] = (if (k == 0 || k == half) mag2 else 2.0 * mag2) * scale
            val mag = kotlin.math.sqrt(mag2)
            val amp = if (k == 0 || k == half) mag / (n * cg) else 2.0 * mag / (n * cg)
            tone[k] = amp * amp / 2.0
        }
        val dt = hop / sampleRate
        val tau = timeConstant.seconds
        var hopMs = 0.0
        val hopStart = n - hop
        for (i in hopStart until n) hopMs += buf[i] * buf[i]
        hopMs /= hop
        splPower = smoothScalar(splPower, hopMs, dt, tau)
        val spl = SpectrumMath.powerToDb(splPower, calibration.broadbandOffset())

        val freqs = SpectrumMath.binFrequencies(n, sampleRate)
        val fullSm = fullSmooth.update(tone, dt, tau)
        val fullInstant = DoubleArray(fullSm.size) { k ->
            SpectrumMath.powerToDb(tone[k], calibration.offsetFor(freqs[k]))
        }
        val fullShownPowerDb = DoubleArray(fullSm.size) { k ->
            SpectrumMath.powerToDb(fullSm[k], calibration.offsetFor(freqs[k]))
        }
        hold(binPeak, fullShownPowerDb, peaksEnabled, high = true)
        hold(binValley, fullShownPowerDb, valleysEnabled, high = false)
        fullAvg.ensure(fullInstant.size)
        if (averagingEnabled) fullAvg.add(fullInstant)
        val fullDisplay = if (averagingEnabled && fullAvg.count > 0) fullAvg.mean() else fullShownPowerDb
        val fullPeakDraw = if (peaksEnabled) binPeak.copyOf() else fullDisplay
        val fullValleyDraw = if (valleysEnabled) binValley.copyOf() else fullDisplay

        val oct1 = bands(IsoBands.octave1, freqs, ms, dt, tau, oct1Smooth, oct1Avg, p1, v1)
        val oct3 = bands(IsoBands.octave3, freqs, ms, dt, tau, oct3Smooth, oct3Avg, p3, v3)
        val oct6 = bands(IsoBands.octave6, freqs, ms, dt, tau, oct6Smooth, oct6Avg, p6, v6)
        val oct12 = bands(IsoBands.octave12, freqs, ms, dt, tau, oct12Smooth, oct12Avg, p12, v12)

        if (capturePeakStore) {
            for (i in peakStore.indices) {
                val level = oct6.trackedDb[i]
                peakStore[i] = if (peakStore[i].isNaN()) level else max(peakStore[i], level)
            }
        }
        val peakCurve = Curve(
            DoubleArray(IsoBands.octave6.size) { IsoBands.octave6[it].centerHz },
            DoubleArray(peakStore.size) { if (peakStore[it].isNaN()) -240.0 else peakStore[it] },
            DoubleArray(peakStore.size) { if (peakStore[it].isNaN()) -240.0 else peakStore[it] },
            DoubleArray(peakStore.size) { if (peakStore[it].isNaN()) -240.0 else peakStore[it] }
        )

        integrateLeq(hopMs, oct1.meanSquare, hop)
        elapsed += dt
        pushChart(spl, maxFrequency(fullDisplay, freqs), toneHz)

        binFreq = freqs
        binDb = fullDisplay
        binPeakOut = if (peaksEnabled) binPeak.copyOf() else fullDisplay.copyOf()
        binValleyOut = if (valleysEnabled) binValley.copyOf() else fullDisplay.copyOf()
        for (i in binPeakOut.indices) if (binPeakOut[i].isNaN()) binPeakOut[i] = fullDisplay[i]
        for (i in binValleyOut.indices) if (binValleyOut[i].isNaN()) binValleyOut[i] = fullDisplay[i]

        val shownFull = SpectrumMath.decimateLogMax(freqs, fullDisplay, fullPeakDraw.clean(fullDisplay), fullValleyDraw.clean(fullDisplay))
        val millis = (System.nanoTime() - started) / 1.0e6
        val frame = DisplayFrame(
            generation = ++generation,
            sampleRate = sampleRate,
            fftSize = fftSize,
            hop = hop,
            splDb = spl,
            scope = DoubleArray(0),
            scopePeak = 1.0,
            maxFreqHz = maxFrequency(fullDisplay, freqs),
            averagingCount = if (averagingEnabled) fullAvg.count else 0,
            processingMillis = millis,
            full = shownFull,
            oct1 = oct1.curve,
            oct3 = oct3.curve,
            oct6 = oct6.curve,
            oct12 = oct12.curve,
            peakStore = peakCurve,
            leq = leqView(),
            chart = chartView(),
            toneHz = toneHz
        )
        last = frame
        return frame
    }

    private data class BandCompute(val curve: Curve, val meanSquare: DoubleArray, val trackedDb: DoubleArray)

    private fun bands(
        definition: List<Band>,
        freqs: DoubleArray,
        ms: DoubleArray,
        dt: Double,
        tau: Double,
        smoother: ExpPowerSmoother,
        averager: DbAverager,
        peak: DoubleArray,
        valley: DoubleArray
    ): BandCompute {
        val power = IsoBands.integrate(freqs, ms, definition)
        val smoothed = smoother.update(power, dt, tau)
        val instant = DoubleArray(definition.size) { i ->
            SpectrumMath.powerToDb(power[i], calibration.offsetFor(definition[i].centerHz))
        }
        val shown = DoubleArray(definition.size) { i ->
            SpectrumMath.powerToDb(smoothed[i], calibration.offsetFor(definition[i].centerHz))
        }
        hold(peak, shown, peaksEnabled, high = true)
        hold(valley, shown, valleysEnabled, high = false)
        if (averagingEnabled) averager.add(instant)
        val display = if (averagingEnabled && averager.count > 0) averager.mean() else shown
        val peakDraw = DoubleArray(definition.size) { i ->
            if (peaksEnabled && !peak[i].isNaN()) peak[i] else display[i]
        }
        val valleyDraw = DoubleArray(definition.size) { i ->
            if (valleysEnabled && !valley[i].isNaN()) valley[i] else display[i]
        }
        return BandCompute(SpectrumMath.bandCurve(definition, display, peakDraw, valleyDraw), power, shown)
    }

    private fun integrateLeq(hopMs: Double, bandMs: DoubleArray, samples: Int) {
        leqMs += hopMs * samples
        leqN += samples
        val n = min(bandMs.size, leqBand.size)
        for (i in 0 until n) leqBand[i] += bandMs[i] * samples
        val elapsedSec = leqN / sampleRate
        if (elapsedSec >= leqDurationSec && leqN > 0) {
            leqHistory.add(snapshotLeq(elapsedSec))
            while (leqHistory.size > 5000) leqHistory.removeAt(0)
            leqMs = 0.0
            leqN = 0
            leqBand.fill(0.0)
            leqStartMs = System.currentTimeMillis()
        }
    }

    private fun snapshotLeq(duration: Double): LeqRecord {
        val broad = SpectrumMath.powerToDb(leqMs / leqN, calibration.broadbandOffset())
        val oct = DoubleArray(IsoBands.octave1.size) { i ->
            SpectrumMath.powerToDb(leqBand[i] / leqN, calibration.offsetFor(IsoBands.octave1[i].centerHz))
        }
        val end = System.currentTimeMillis()
        return LeqRecord(leqStartMs, end, duration, broad, oct)
    }

    private fun leqView(): LeqView {
        val elapsedSec = if (leqN == 0L) 0.0 else leqN / sampleRate
        val broad = if (leqN == 0L) calibration.broadbandOffset() - 240.0
        else SpectrumMath.powerToDb(leqMs / leqN, calibration.broadbandOffset())
        val oct = DoubleArray(IsoBands.octave1.size) { i ->
            if (leqN == 0L) -240.0
            else SpectrumMath.powerToDb(leqBand[i] / leqN, calibration.offsetFor(IsoBands.octave1[i].centerHz))
        }
        return LeqView(elapsedSec, leqDurationSec, broad, oct, leqHistory.takeLast(8))
    }

    private fun pushChart(spl: Double, peakHz: Double, sweep: Double) {
        val write = if (chartSize < chartTime.size) {
            val slot = (chartStart + chartSize) % chartTime.size
            chartSize++
            slot
        } else {
            val slot = chartStart
            chartStart = (chartStart + 1) % chartTime.size
            slot
        }
        chartTime[write] = elapsed
        chartSpl[write] = spl
        chartPeak[write] = peakHz
        chartSweep[write] = sweep
    }

    private fun chartView(): ChartView {
        val points = min(800, chartSize)
        val ago = DoubleArray(points)
        val spl = DoubleArray(points)
        val peak = DoubleArray(points)
        val sweep = DoubleArray(points)
        if (points == 0) return ChartView(ago, spl, peak, sweep)
        val now = chartTime[(chartStart + chartSize - 1) % chartTime.size]
        for (i in 0 until points) {
            val src = if (points == 1) 0 else (i.toLong() * (chartSize - 1) / (points - 1)).toInt()
            val idx = (chartStart + src) % chartTime.size
            ago[i] = now - chartTime[idx]
            spl[i] = chartSpl[idx]
            peak[i] = chartPeak[idx]
            sweep[i] = chartSweep[idx]
        }
        return ChartView(ago, spl, peak, sweep)
    }

    private fun maxFrequency(db: DoubleArray, freq: DoubleArray): Double {
        var best = 1
        var level = -1.0e9
        for (k in 1 until min(db.size, freq.size) - 1) {
            val f = freq[k]
            if (f < 20.0 || f > 20000.0) continue
            if (db[k] > level) {
                level = db[k]
                best = k
            }
        }
        val frac = SpectrumMath.parabolicBin(db, best)
        return frac * sampleRate / fftSize
    }

    private fun withScope(frame: DisplayFrame?): DisplayFrame {
        val (samples, peak) = snapshotScope()
        if (frame == null) {
            val silent = DoubleArray(10) { -240.0 }
            val freq = DoubleArray(10) { IsoBands.octave1[it].centerHz }
            val empty = Curve(freq, silent, silent.copyOf(), silent.copyOf())
            val none = Curve(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
            return DisplayFrame(
                generation = ++generation,
                sampleRate = sampleRate,
                fftSize = fftSize,
                hop = hop,
                splDb = calibration.broadbandOffset() - 240.0,
                scope = samples,
                scopePeak = peak,
                maxFreqHz = 0.0,
                averagingCount = 0,
                processingMillis = 0.0,
                full = none,
                oct1 = empty,
                oct3 = Curve(
                    DoubleArray(IsoBands.octave3.size) { IsoBands.octave3[it].centerHz },
                    DoubleArray(IsoBands.octave3.size) { -240.0 },
                    DoubleArray(IsoBands.octave3.size) { -240.0 },
                    DoubleArray(IsoBands.octave3.size) { -240.0 }
                ),
                oct6 = Curve(
                    DoubleArray(IsoBands.octave6.size) { IsoBands.octave6[it].centerHz },
                    DoubleArray(IsoBands.octave6.size) { -240.0 },
                    DoubleArray(IsoBands.octave6.size) { -240.0 },
                    DoubleArray(IsoBands.octave6.size) { -240.0 }
                ),
                oct12 = Curve(
                    DoubleArray(IsoBands.octave12.size) { IsoBands.octave12[it].centerHz },
                    DoubleArray(IsoBands.octave12.size) { -240.0 },
                    DoubleArray(IsoBands.octave12.size) { -240.0 },
                    DoubleArray(IsoBands.octave12.size) { -240.0 }
                ),
                peakStore = Curve(
                    DoubleArray(IsoBands.octave6.size) { IsoBands.octave6[it].centerHz },
                    DoubleArray(IsoBands.octave6.size) { -240.0 },
                    DoubleArray(IsoBands.octave6.size) { -240.0 },
                    DoubleArray(IsoBands.octave6.size) { -240.0 }
                ),
                leq = leqView(),
                chart = chartView(),
                toneHz = toneHz
            )
        }
        return frame.copy(generation = ++generation, scope = samples, scopePeak = peak, toneHz = toneHz)
    }

    private fun appendScope(raw: DoubleArray) {
        for (v in raw) {
            scope[scopeWrite] = v
            scopeWrite = (scopeWrite + 1) % scope.size
            if (scopeCount < scope.size) scopeCount++
        }
    }

    private fun snapshotScope(): Pair<DoubleArray, Double> {
        val points = 400
        val out = DoubleArray(points)
        if (scopeCount == 0) return out to 1.0
        var peak = 1e-6
        for (i in 0 until scopeCount) {
            val v = kotlin.math.abs(scopeElem(i))
            if (v > peak) peak = v
        }
        val scale = peak * 1.1
        for (p in 0 until points) {
            val idx = (p.toDouble() / points * scopeCount).toInt().coerceIn(0, scopeCount - 1)
            out[p] = (scopeElem(idx) / scale).coerceIn(-1.0, 1.0)
        }
        return out to peak
    }

    private fun scopeElem(chronological: Int): Double {
        val start = if (scopeCount < scope.size) 0 else scopeWrite
        return scope[(start + chronological) % scope.size]
    }

    private fun ensure(capacity: Int) {
        if (buf.size >= capacity) return
        var n = buf.size
        while (n < capacity) n *= 2
        buf = buf.copyOf(n)
    }

    private fun resetSpectrumState() {
        filter.reset()
        gains = SpectrumMath.windowGains(fftSize)
        buf = DoubleArray(fftSize * 2)
        len = 0
        fullSmooth = ExpPowerSmoother(fftSize / 2 + 1)
        oct1Smooth.reset(); oct3Smooth.reset(); oct6Smooth.reset(); oct12Smooth.reset()
        splPower = Double.NaN
        resetHolds()
        resetAveragers()
        binFreq = DoubleArray(0)
    }

    private fun resetHolds() {
        binPeak.fill(Double.NaN)
        binValley.fill(Double.NaN)
        p1.fill(Double.NaN); v1.fill(Double.NaN)
        p3.fill(Double.NaN); v3.fill(Double.NaN)
        p6.fill(Double.NaN); v6.fill(Double.NaN)
        p12.fill(Double.NaN); v12.fill(Double.NaN)
    }

    private fun resetAveragers() {
        fullAvg.reset(); oct1Avg.reset(); oct3Avg.reset(); oct6Avg.reset(); oct12Avg.reset()
    }

    private fun hold(dest: DoubleArray, values: DoubleArray, enabled: Boolean, high: Boolean) {
        if (!enabled) return
        val n = min(dest.size, values.size)
        for (i in 0 until n) {
            val v = values[i]
            dest[i] = if (dest[i].isNaN()) v else if (high) max(dest[i], v) else min(dest[i], v)
        }
    }

    private fun smoothScalar(prev: Double, value: Double, dt: Double, tau: Double): Double {
        val current = if (prev.isNaN()) 0.0 else prev
        val decay = exp(-dt / tau.coerceAtLeast(1e-4))
        return current * decay + value * (1.0 - decay)
    }

    private fun nan(n: Int) = DoubleArray(n) { Double.NaN }

    private fun DoubleArray.clean(fallback: DoubleArray): DoubleArray {
        val out = copyOf()
        for (i in out.indices) if (out[i].isNaN()) out[i] = fallback.getOrElse(i) { -240.0 }
        return out
    }
}
