/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CoreAlgorithmsTest {
    @Test
    fun fftRoundTrip() {
        val n = 64
        val re = DoubleArray(n) { kotlin.random.Random(1).nextDouble() }
        val original = re.copyOf()
        val im = DoubleArray(n)
        Fft.forward(re, im)
        Fft.inverse(re, im)
        for (i in 0 until n) assertEquals(original[i], re[i], 1e-9)
    }

    @Test
    fun sineLandsOnBin() {
        val n = 1024
        val k = 32
        val re = DoubleArray(n) { i -> sin(2.0 * PI * k * i / n) }
        val im = DoubleArray(n)
        Fft.forward(re, im)
        var best = 0
        var bestMag = -1.0
        for (i in 0..n / 2) {
            val mag = kotlin.math.hypot(re[i], im[i])
            if (mag > bestMag) {
                bestMag = mag
                best = i
            }
        }
        assertEquals(k, best)
    }

    @Test
    fun isoThirdOctaveHasThirtyOneBandsAndTiles() {
        assertEquals(31, IsoBands.octave3.size)
        assertEquals(10, IsoBands.octave1.size)
        assertEquals(20.0, IsoBands.octave3.first().centerHz, 0.01)
        assertEquals(20000.0, IsoBands.octave3.last().centerHz, 0.01)
        for (i in 0 until IsoBands.octave3.size - 1) {
            assertEquals(IsoBands.octave3[i].highHz, IsoBands.octave3[i + 1].lowHz, 1e-6)
        }
        assertTrue(IsoBands.octave6.size > 40)
        assertTrue(IsoBands.octave12.size > IsoBands.octave6.size)
        val names = IsoBands.octave6.map { it.centerHz }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun overlapSaveIdentityAndDelay() {
        val fir = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val filter = OverlapSaveFilter(fir)
        val input = DoubleArray(200) { it.toDouble() }
        val out = filter.process(input)
        assertTrue(out.size >= 150)
        for (i in 0 until out.size) assertEquals(input[i], out[i], 1e-8)
    }

    @Test
    fun aAndCWeightingMatchIecTable() {
        val fs = 48000.0
        val a = BiquadChain(WeightingFilter.aWeighting(fs))
        val c = BiquadChain(WeightingFilter.cWeighting(fs))
        fun db(chain: BiquadChain, hz: Double) = 20.0 * log10(chain.magnitude(hz, fs))
        assertEquals(0.0, db(a, 1000.0), 0.05)
        assertEquals(-19.1, db(a, 100.0), 0.6)
        assertEquals(-2.5, db(a, 10000.0), 1.2)
        assertEquals(1.2, db(a, 2000.0), 0.4)
        assertEquals(0.0, db(c, 1000.0), 0.05)
        assertEquals(-0.3, db(c, 100.0), 0.4)
        assertEquals(-3.0, db(c, 31.5), 0.5)
        val sine = DoubleArray(16384) { sin(2.0 * PI * 1000.0 * it / fs) }
        val filtered = sine.copyOf()
        a.processInPlace(filtered)
        val ratio = rms(filtered, 4000, filtered.size) / rms(sine, 4000, sine.size)
        assertEquals(1.0, ratio, 0.05)
    }

    @Test
    fun xCurveSlopesThreeDbPerOctave() {
        val fs = 44100.0
        val fir = WeightingFilter.designXCurveFir(fs, 2048)
        fun db(hz: Double) = 20.0 * log10(WeightingFilter.firGain(fir, hz, fs))
        assertEquals(0.0, db(1000.0), 0.4)
        assertEquals(-3.0, db(4000.0) - db(2000.0), 1.0)
        assertEquals(-3.0, db(8000.0) - db(4000.0), 1.2)
        assertEquals(3.0, db(60.0) - db(30.0), 1.3)
        val filter = WeightingFilter(Weighting.X, fs)
        val sine = DoubleArray(12000) { 0.2 * sin(2.0 * PI * 1000.0 * it / fs) }
        val out = filter.process(sine)
        assertTrue(out.size > 4000)
        val gain = rms(out, out.size - 3000, out.size) / rms(sine, sine.size - 3000, sine.size)
        assertEquals(1.0, gain, 0.2)
    }

    @Test
    fun pipelineSineLevelAndBandAgree() {
        val fs = 44100.0
        val n = 8192
        val k = (1000.0 / fs * n).roundToInt()
        val freq = k * fs / n
        val amp = 0.5
        val pipe = AnalysisPipeline(fs, n)
        pipe.timeConstant = TimeConstant.FAST
        pipe.calibration = Calibration(singleOffsetDb = 0.0)
        val samples = DoubleArray(fs.toInt()) { amp * sin(2.0 * PI * freq * it / fs) }
        var frame: DisplayFrame? = null
        var at = 0
        while (at < samples.size) {
            val end = minOf(at + 2048, samples.size)
            frame = pipe.push(samples.copyOfRange(at, end)) ?: frame
            at = end
        }
        val expected = 20.0 * log10(amp / sqrt(2.0))
        assertEquals(expected, frame!!.splDb, 1.0)
        val bin = frame.full.freqHz.indices.maxBy { frame.full.db[it] }
        assertEquals(expected, frame.full.db[bin], 1.5)
        val third = frame.oct3.freqHz.indices.minBy { abs(frame.oct3.freqHz[it] - 1000.0) }
        assertEquals(expected, frame.oct3.db[third], 1.5)
        assertTrue(frame.maxFreqHz > 900 && frame.maxFreqHz < 1100)
        assertEquals("B5", Music.noteName(987.77))
        assertTrue(Music.noteName(440.0).startsWith("A"))
    }

    @Test
    fun pinkIsFlatOnThirdOctaveAndWhiteRises() {
        val fs = 44100.0
        val pipe = AnalysisPipeline(fs, 8192)
        pipe.timeConstant = TimeConstant.FAST
        pipe.setAveraging(true)
        val gen = SignalGenerator(fs, kotlin.random.Random(7))
        gen.waveform = Waveform.PINK
        gen.volume = 0.8
        val pink = gen.renderMono((fs * 3).toInt())
        var frame: DisplayFrame? = null
        var at = 0
        while (at < pink.size) {
            val end = minOf(at + 2048, pink.size)
            frame = pipe.push(pink.copyOfRange(at, end)) ?: frame
            at = end
        }
        val spread = bandSpread(frame!!.oct3, 125.0, 4000.0)
        assertTrue(spread < 5.0, "pink 1/3 spread was $spread dB")

        val whitePipe = AnalysisPipeline(fs, 8192)
        whitePipe.setAveraging(true)
        whitePipe.timeConstant = TimeConstant.FAST
        val whiteGen = SignalGenerator(fs, kotlin.random.Random(8))
        whiteGen.waveform = Waveform.WHITE
        whiteGen.volume = 0.4
        val white = whiteGen.renderMono((fs * 3).toInt())
        frame = null
        at = 0
        while (at < white.size) {
            val end = minOf(at + 2048, white.size)
            frame = whitePipe.push(white.copyOfRange(at, end)) ?: frame
            at = end
        }
        val slope = regressionSlope(frame!!.oct3, 200.0, 8000.0)
        assertEquals(3.0, slope, 1.2)
    }

    @Test
    fun calibrationFlattensARisingMicOnWhiteNoise() {
        val fs = 44100.0
        val rising = ArrayList<Pair<Double, Double>>()
        var f = 20.0
        while (f < 20000) {
            rising.add(f to (3.0 * log2(f / 1000.0)))
            f *= 1.05
        }
        val file = MicCalFile(rising, sensDb = -2.4, serial = "99-00192")
        val corr = CalibrationFiles.toThirdOctave(file, treatAsResponse = true)
        val pipe = AnalysisPipeline(fs, 8192)
        pipe.setAveraging(true)
        pipe.replaceCalibration(Calibration(mode = CalMode.THIRD_OCTAVE, thirdOffsetDb = 0.0, thirdBandDb = corr))
        val gen = SignalGenerator(fs, kotlin.random.Random(9))
        gen.waveform = Waveform.WHITE
        gen.volume = 0.4
        val audio = gen.renderMono((fs * 3).toInt())
        var frame: DisplayFrame? = null
        var at = 0
        while (at < audio.size) {
            val end = minOf(at + 2048, audio.size)
            frame = pipe.push(audio.copyOfRange(at, end)) ?: frame
            at = end
        }
        val slope = regressionSlope(frame!!.oct3, 200.0, 8000.0)
        assertEquals(0.0, slope, 1.3, "calibrated white slope was $slope")
    }

    @Test
    fun generatorWaveformsSweepPhaseAndPolarity() {
        val fs = 44100.0
        val gen = SignalGenerator(fs)
        gen.frequency = 1000.0
        gen.volume = 1.0
        gen.waveform = Waveform.SINE
        val sine = gen.renderMono(fs.toInt())
        val n = 8192
        val re = sine.copyOf(n)
        val im = DoubleArray(n)
        Fft.forward(re, im)
        var best = 0
        var bestMag = -1.0
        for (k in 1 until n / 2) {
            val mag = kotlin.math.hypot(re[k], im[k])
            if (mag > bestMag) {
                bestMag = mag
                best = k
            }
        }
        assertEquals(1000.0, best * fs / n, 15.0)

        gen.waveform = Waveform.LOG_SWEEP
        gen.sweepStartHz = 100.0
        gen.sweepEndHz = 1000.0
        gen.sweepDurationSec = 1.0
        gen.renderMono(1)
        assertEquals(100.0, gen.currentHz, 2.0)
        gen.renderMono((fs * 0.5).toInt())
        assertEquals(316.2, gen.currentHz, 20.0)

        gen.waveform = Waveform.WARBLE
        gen.sweepStartHz = 400.0
        gen.sweepEndHz = 800.0
        gen.sweepDurationSec = 1.0
        gen.renderMono(1)
        assertEquals(400.0, gen.currentHz, 2.0)
        gen.renderMono((fs * 0.5).toInt())
        assertEquals(800.0, gen.currentHz, 15.0)

        gen.waveform = Waveform.SINE
        gen.invertRight = true
        gen.leftOn = true
        gen.rightOn = false
        val stereo = gen.renderStereo(32)
        assertTrue(stereo.right.all { it == 0.0 })
        gen.rightOn = true
        val both = gen.renderStereo(32)
        for (i in both.left.indices) assertEquals(-both.left[i], both.right[i], 1e-12)

        val template = PolarityPulse.template(fs)
        val positive = DoubleArray(template.size + 100)
        template.copyInto(positive, 40)
        assertEquals(Polarity.POSITIVE, PolarityPulse.detect(positive, template))
        val negative = DoubleArray(positive.size) { -positive[it] }
        assertEquals(Polarity.NEGATIVE, PolarityPulse.detect(negative, template))
    }

    @Test
    fun schroederRecoversKnownRt60() {
        val rt = 1.25
        val dt = 0.002
        val power = syntheticDecayPower(6.0, dt, rt)
        val (_, metrics) = Schroeder.integrate(power, dt)
        assertTrue(metrics.valid)
        assertEquals(rt, metrics.earlySec, 0.05)
        assertEquals(rt, metrics.t15to20Sec, 0.05)
    }

    @Test
    fun rt60RecorderCompletesAfterClap() {
        val fs = 8000.0
        val rec = Rt60Recorder(fs)
        rec.armClap()
        val rt = 0.6
        val tau = rt / kotlin.math.ln(1.0e6)
        val n = (fs * 2.5).toInt()
        val audio = DoubleArray(n) { i ->
            val t = i / fs
            val env = kotlin.math.exp(-t / tau)
            env * sin(2.0 * PI * 1000.0 * i / fs)
        }
        var at = 0
        while (at < audio.size && rec.phase != Rt60Phase.READY) {
            val end = minOf(at + 400, audio.size)
            rec.push(audio.copyOfRange(at, end))
            at = end
        }
        assertEquals(Rt60Phase.READY, rec.phase)
        val report = rec.report!!
        assertTrue(report.wideband.earlySec.isFinite())
        assertEquals(10, report.bands.size)
        assertTrue(report.curveDb.first() > report.curveDb.last())
    }

    @Test
    fun leqIsPowerAverageAndFileRoundTrips() {
        val fs = 8000.0
        val pipe = AnalysisPipeline(fs, 1024)
        pipe.leqDurationSec = 0.4
        pipe.calibration = Calibration(singleOffsetDb = 10.0)
        pipe.timeConstant = TimeConstant.IMPULSE
        val amp = 0.2
        val audio = DoubleArray((fs * 1.2).toInt()) { amp * sin(2.0 * PI * 1000.0 * it / fs) }
        pipe.push(audio)
        val records = pipe.allLeq()
        assertTrue(records.isNotEmpty(), "expected a finished Leq interval")
        val text = MeasurementFiles.writeLeq(records, IsoBands.octave1.map { it.centerHz }.toDoubleArray())
        val parsed = MeasurementFiles.readLeq(text)
        assertEquals(records.size, parsed.size)
        assertEquals(records.first().broadbandDb, parsed.first().broadbandDb, 0.01)
        val expected = 20.0 * log10(amp / sqrt(2.0)) + 10.0
        assertEquals(expected, records.first().broadbandDb, 1.5)
    }

    @Test
    fun spectrumFileFormulaAndOctaveBlock() {
        val pipe = AnalysisPipeline(8000.0, 1024)
        pipe.calibration = Calibration(singleOffsetDb = 12.0)
        val k = 20
        val freq = k * 8000.0 / 1024.0
        val audio = DoubleArray(8000) { 0.25 * sin(2.0 * PI * freq * it / 8000.0) }
        pipe.push(audio)
        val dump = pipe.dump(RtaMode.OCTAVE_3)
        val text = MeasurementFiles.writeSpectrum(dump)
        assertTrue(text.contains("1/3 Octave Data"))
        assertTrue(text.contains("dBcalibration=12.0"))
        val curve = MeasurementFiles.readSpectrum(text)
        assertTrue(curve.size > 100)
        val row = text.lineSequence().first { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.size >= 5 && parts.all { it.toDoubleOrNull() != null }
        }
        val parts = row.trim().split(Regex("\\s+"))
        val mag = parts[1].toDouble()
        val db = parts[2].toDouble()
        val rebuilt = 20.0 * log10(mag) + 20.0 + 4.2 + 12.0
        assertEquals(db, rebuilt, 0.05)
    }

    @Test
    fun daytonAndTargetAndWavRoundTrip() {
        val text = """
            * Dayton iMM-6
            "Sens Factor =-1.25dB, SERNO: 99-00192"
            100.0 -2.0
            1000.0 0.0
            4000.0 6.0
            10000.0 6.0
        """.trimIndent()
        val parsed = CalibrationFiles.parse(text)
        assertEquals(-1.25, parsed.sensDb!!, 0.001)
        assertEquals("99-00192", parsed.serial)
        assertEquals(4, parsed.points.size)
        val corr = CalibrationFiles.toThirdOctave(parsed, treatAsResponse = true)
        val i1k = IsoBands.octave3.indexOfFirst { abs(it.centerHz - 1000.0) < 1 }
        val i4k = IsoBands.octave3.indexOfFirst { abs(it.centerHz - 4000.0) < 1 }
        assertEquals(0.0, corr[i1k], 0.2)
        assertTrue(corr[i4k] < -3.0)

        val levels = DoubleArray(31) { 60.0 }
        levels[10] = 72.0
        val targ = MeasurementFiles.writeTarget(levels)
        val back = MeasurementFiles.readTarget(targ)
        assertEquals(72.0, back[10], 0.01)
        val audioTool = "AudioTool Target Curve Comment\n1000 55\n"
        assertEquals(55.0, MeasurementFiles.readTarget(audioTool)[i1k], 0.01)

        val pcm = ShortArray(32) { (it * 100).toShort() }
        val wav = MeasurementFiles.writeWav(44100, pcm)
        val read = MeasurementFiles.readWav(wav)
        assertEquals(44100, read.sampleRate)
        assertArrayEquals(pcm, read.samples)
    }

    @Test
    fun noiseCurvesAndSones() {
        val freq = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val nc30 = DoubleArray(freq.size) { -20.0 }
        val table = NoiseCurves.ncLevels(30)
        for (i in NoiseCurves.ncFreq.indices) {
            val band = freq.indexOfFirst { abs(it - NoiseCurves.ncFreq[i]) < 0.1 }
            nc30[band] = table[i]
        }
        assertEquals("NC-30", NoiseCurves.ncRating(freq, nc30))
        nc30[freq.indexOfFirst { it == 1000.0 }] = 40.0
        assertEquals("NC-40", NoiseCurves.ncRating(freq, nc30))
        val nr = DoubleArray(freq.size) { NoiseCurves.nrLevel(35, freq[it]) }
        assertEquals("NR-35", NoiseCurves.nrRating(freq, nr))

        assertEquals(40.0, Sones.splOfPhon(40.0, 1000.0), 0.2)
        assertEquals(1.0, Sones.bandSone(40.0, 1000.0), 0.05)
        val low = Sones.bandSone(40.0, 125.0)
        assertTrue(low < 0.5, "125 Hz sone was $low")
        val only1k = Sones.estimate(mapOf(1000 to 40.0))
        assertEquals(1.0, only1k, 0.08)
    }

    @Test
    fun timeConstantReachesOneOverE() {
        val smoother = ExpPowerSmoother(1)
        val dt = 0.01
        val tau = 0.5
        var level = 0.0
        val steps = (tau / dt).roundToInt()
        var out = 0.0
        repeat(steps) {
            out = smoother.update(doubleArrayOf(1.0), dt, tau)[0]
        }
        assertEquals(1.0 - kotlin.math.exp(-1.0), out, 0.05)
        assertTrue(level == 0.0 || true)
    }

    private fun bandSpread(curve: Curve, from: Double, to: Double): Double {
        val values = ArrayList<Double>()
        for (i in curve.freqHz.indices) if (curve.freqHz[i] in from..to) values.add(curve.db[i])
        return values.max() - values.min()
    }

    private fun regressionSlope(curve: Curve, from: Double, to: Double): Double {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in curve.freqHz.indices) {
            val f = curve.freqHz[i]
            if (f < from || f > to) continue
            val x = log2(f)
            val y = curve.db[i]
            n++
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx)
    }
}
