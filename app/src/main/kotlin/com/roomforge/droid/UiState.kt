/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid

import com.roomforge.core.CalMode
import com.roomforge.core.Calibration
import com.roomforge.core.RtaMode
import com.roomforge.core.TimeConstant
import com.roomforge.core.Waveform
import com.roomforge.core.Weighting

enum class Screen { ANALYZER, GENERATOR, RT60, CAL, TARGET, FILES }

enum class MicKind { MAIN, FRONT, WIRED, USB, BLUETOOTH }

data class MicChoice(val kind: MicKind, val deviceId: Int, val label: String)

data class UiSettings(
    val screen: Screen = Screen.ANALYZER,
    val mode: RtaMode = RtaMode.OCTAVE_3,
    val running: Boolean = true,
    val averaging: Boolean = false,
    val peaks: Boolean = true,
    val valleys: Boolean = false,
    val speed: TimeConstant = TimeConstant.MEDIUM,
    val weighting: Weighting = Weighting.FLAT,
    val fftSize: Int = 8192,
    val micKind: MicKind = MicKind.MAIN,
    val micDeviceId: Int = -1,
    val micLabel: String = "Builtin Main",
    val sampleRate: Int = 44100,
    val hideInput: Boolean = false,
    val showTransport: Boolean = false,
    val showCursors: Boolean = false,
    val showNotes: Boolean = true,
    val showSone: Boolean = false,
    val showNc: Boolean = false,
    val showNr: Boolean = false,
    val showTarget: Boolean = true,
    val freqMin: Double = 20.0,
    val freqMax: Double = 20000.0,
    val freqLinear: Boolean = false,
    val dbMin: Double = 20.0,
    val dbMax: Double = 110.0,
    val dbStep: Double = 15.0,
    val cursorA: Double = 200.0,
    val cursorB: Double = 2000.0,
    val chartSpan: Double = 0.0,
    val spectrogramFast: Boolean = true,
    val waterfall: Boolean = false,
    val leqDuration: Double = 60.0,
    val keepScreenOn: Boolean = true,
    val autoUpdate: Boolean = true,
    val lockRotation: Boolean = false,
    val fontIndex: Int = 1,
    val calMode: CalMode = CalMode.SINGLE,
    val singleOffset: Double = 0.0,
    val thirdOffset: Double = 0.0,
    val thirdBands: List<Double> = List(31) { 0.0 },
    val target: List<Double> = List(31) { 60.0 },
    val sensDb: Double? = null,
    val generatorOn: Boolean = false,
    val waveform: Waveform = Waveform.SINE,
    val genHz: Double = 1000.0,
    val volume: Float = 0.2f,
    val leftOn: Boolean = true,
    val rightOn: Boolean = true,
    val invertRight: Boolean = false,
    val sweepStart: Double = 100.0,
    val sweepEnd: Double = 2000.0,
    val sweepDur: Double = 10.0,
    val impulseSec: Double = 1.0,
    val buttonsVisible: Boolean = true,
    val rt60Table: Boolean = true,
    val status: String = "",
    val overlayEpoch: Int = 0,
    val loadedNameA: String? = null,
    val loadedNameB: String? = null,
    val loadedSwpName: String? = null,
    val recording: Boolean = false,
    val recordSeconds: Double = 0.0,
    val playing: Boolean = false,
    val wavName: String? = null,
    val polarity: String? = null,
    val frameMillis: Double = 0.0
) {
    fun calibration(): Calibration = Calibration(
        mode = calMode,
        singleOffsetDb = singleOffset,
        thirdOffsetDb = thirdOffset,
        thirdBandDb = DoubleArray(31) { i -> thirdBands.getOrElse(i) { 0.0 } }
    )

    fun fontScale(): Float = when (fontIndex % 4) {
        0 -> 0.85f
        1 -> 1.05f
        2 -> 1.4f
        else -> 1.8f
    }

    fun fontName(): String = when (fontIndex % 4) {
        0 -> "小"
        1 -> "大"
        2 -> "超大"
        else -> "巨大"
    }
}

data class Rt60Ui(
    val phase: com.roomforge.core.Rt60Phase = com.roomforge.core.Rt60Phase.IDLE,
    val report: com.roomforge.core.Rt60Report? = null,
    val liveDb: Double = -120.0
)

/** Values the capture thread may publish without rewriting [UiSettings]. */
data class LiveReadout(
    val frameMillis: Double = 0.0,
    val polarity: String? = null,
    val recordSeconds: Double = 0.0
)
