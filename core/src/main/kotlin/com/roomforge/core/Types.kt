/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

enum class Weighting { FLAT, A, C, X }

enum class TimeConstant(val seconds: Double) {
    FAST(0.125),
    MEDIUM(0.5),
    SLOW(1.0),
    IMPULSE(0.025)
}

enum class RtaMode {
    OCTAVE_1,
    OCTAVE_3,
    OCTAVE_6,
    OCTAVE_12,
    FULL,
    SPECTROGRAM,
    WATERFALL,
    SPL,
    LEQ,
    CHART,
    PEAK_STORE
}

enum class CalMode { SINGLE, THIRD_OCTAVE }

enum class Waveform {
    SINE,
    SQUARE,
    TRIANGLE,
    RAMP,
    IMPULSE,
    SILENCE,
    WHITE,
    PINK,
    SWEEP,
    LOG_SWEEP,
    WARBLE,
    POLARITY
}

enum class Polarity { POSITIVE, NEGATIVE, UNKNOWN }

data class Band(val centerHz: Double, val lowHz: Double, val highHz: Double)

data class Curve(
    val freqHz: DoubleArray,
    val db: DoubleArray,
    val peakDb: DoubleArray,
    val valleyDb: DoubleArray
) {
    val size: Int get() = freqHz.size

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class LeqRecord(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val durationSec: Double,
    val broadbandDb: Double,
    val octaveDb: DoubleArray
)

data class ChartSample(
    val timeSec: Double,
    val splDb: Double,
    val peakHz: Double,
    val sweepHz: Double
)

data class LeqView(
    val elapsedSec: Double,
    val durationSec: Double,
    val broadbandDb: Double,
    val octaveDb: DoubleArray,
    val recent: List<LeqRecord>
)

data class ChartView(
    val secondsAgo: DoubleArray,
    val splDb: DoubleArray,
    val peakHz: DoubleArray,
    val sweepHz: DoubleArray
)

data class DisplayFrame(
    val generation: Long,
    val sampleRate: Double,
    val fftSize: Int,
    val hop: Int,
    val splDb: Double,
    val scope: DoubleArray,
    val scopePeak: Double,
    val maxFreqHz: Double,
    val averagingCount: Int,
    val processingMillis: Double,
    val full: Curve,
    val oct1: Curve,
    val oct3: Curve,
    val oct6: Curve,
    val oct12: Curve,
    val peakStore: Curve,
    val leq: LeqView,
    val chart: ChartView,
    val toneHz: Double
)

data class SpectrumDump(
    val sampleRate: Double,
    val fftSize: Int,
    val weighting: Weighting,
    val globalCalibrationDb: Double,
    val binFreqHz: DoubleArray,
    val binMagnitude: DoubleArray,
    val binDb: DoubleArray,
    val binPeakDb: DoubleArray,
    val binValleyDb: DoubleArray,
    val bandTitle: String?,
    val bandFreqHz: DoubleArray?,
    val bandDb: DoubleArray?,
    val bandValleyDb: DoubleArray?,
    val bandPeakDb: DoubleArray?
)

data class Calibration(
    val mode: CalMode = CalMode.SINGLE,
    val singleOffsetDb: Double = 0.0,
    val thirdOffsetDb: Double = 0.0,
    val thirdBandDb: DoubleArray = DoubleArray(IsoBands.octave3.size)
) {
    fun broadbandOffset(): Double = when (mode) {
        CalMode.SINGLE -> singleOffsetDb
        CalMode.THIRD_OCTAVE -> thirdOffsetDb
    }

    fun offsetFor(freqHz: Double): Double = when (mode) {
        CalMode.SINGLE -> singleOffsetDb
        CalMode.THIRD_OCTAVE -> thirdOffsetDb + IsoBands.correctionAt(freqHz, thirdBandDb)
    }

    fun copyBands(bands: DoubleArray, offset: Double = thirdOffsetDb): Calibration =
        Calibration(mode, singleOffsetDb, offset, bands.copyOf())
}

data class Stereo(val left: DoubleArray, val right: DoubleArray)
