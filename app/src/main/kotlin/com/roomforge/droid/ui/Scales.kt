/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.ui.geometry.Offset
import com.roomforge.droid.UiSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal fun xOf(freq: Double, min: Double, max: Double, linear: Boolean, left: Float, width: Float): Float {
    val f0 = min.coerceAtLeast(1.0)
    val f1 = max.coerceAtLeast(f0 * 1.001)
    val t = if (linear) {
        (freq - f0) / (f1 - f0)
    } else {
        (ln(freq.coerceAtLeast(1.0)) - ln(f0)) / (ln(f1) - ln(f0))
    }
    return left + (t.coerceIn(-0.05, 1.05) * width).toFloat()
}

internal fun freqAt(x: Float, min: Double, max: Double, linear: Boolean, left: Float, width: Float): Double {
    val f0 = min.coerceAtLeast(1.0)
    val f1 = max.coerceAtLeast(f0 * 1.001)
    val t = ((x - left) / width.coerceAtLeast(1f)).coerceIn(0f, 1f).toDouble()
    return if (linear) f0 + (f1 - f0) * t else exp(ln(f0) + (ln(f1) - ln(f0)) * t)
}

internal fun yOf(db: Double, dbMin: Double, dbMax: Double, top: Float, height: Float): Float {
    val span = (dbMax - dbMin).coerceAtLeast(1.0)
    val t = ((db - dbMin) / span).coerceIn(-0.25, 1.25)
    return top + ((1.0 - t) * height).toFloat()
}

internal fun dbAt(y: Float, dbMin: Double, dbMax: Double, top: Float, height: Float): Double {
    val t = ((y - top) / height.coerceAtLeast(1f)).coerceIn(0f, 1f).toDouble()
    return dbMax - t * (dbMax - dbMin)
}

internal fun scaleSettings(
    s: UiSettings,
    pan: Offset,
    zoom: Float,
    viewWidth: Float,
    viewHeight: Float,
    focus: Offset
): UiSettings {
    val left = 42f
    val bottom = 18f
    val plotW = (viewWidth - left).coerceAtLeast(1f)
    val plotH = (viewHeight - bottom).coerceAtLeast(1f)
    val z = zoom.toDouble().coerceIn(0.25, 4.0)
    val focusF = freqAt(focus.x, s.freqMin, s.freqMax, s.freqLinear, left, plotW)
    val focusT = ((focus.x - left) / plotW).coerceIn(0f, 1f).toDouble()
    val (fMin0, fMax0) = if (s.freqLinear) {
        val span = (s.freqMax - s.freqMin).coerceAtLeast(1.0) / z
        val n0 = focusF - focusT * span
        n0 to n0 + span
    } else {
        val a = ln(s.freqMin.coerceAtLeast(1.0))
        val b = ln(s.freqMax.coerceAtLeast(s.freqMin * 1.01))
        val span = (b - a) / z
        val n0 = ln(focusF.coerceAtLeast(1.0)) - focusT * span
        exp(n0) to exp(n0 + span)
    }
    val freqShift = if (s.freqLinear) {
        -pan.x / plotW * (fMax0 - fMin0)
    } else {
        val span = ln(fMax0 / fMin0.coerceAtLeast(1.0))
        val shifted = exp(ln(fMin0.coerceAtLeast(1.0)) - pan.x / plotW * span)
        shifted - fMin0
    }
    val (f0, f1) = clampFreq(fMin0 + freqShift, fMax0 + freqShift)

    val dbSpan = (s.dbMax - s.dbMin).coerceAtLeast(6.0) / z
    val focusDb = dbAt(focus.y, s.dbMin, s.dbMax, 0f, plotH)
    val ty = ((focus.y) / plotH).coerceIn(0f, 1f).toDouble()
    var dMax = focusDb + ty * dbSpan
    var dMin = dMax - dbSpan
    val dShift = pan.y / plotH * dbSpan
    dMin += dShift
    dMax += dShift
    if (dMax - dMin < 6.0) dMax = dMin + 6.0
    dMin = dMin.coerceIn(-60.0, 160.0)
    dMax = dMax.coerceIn(dMin + 6.0, 180.0)
    return s.copy(freqMin = f0, freqMax = f1, dbMin = dMin, dbMax = dMax)
}

internal fun moveCursor(s: UiSettings, x: Float, viewWidth: Float, which: Int): UiSettings {
    val freq = freqAt(x, s.freqMin, s.freqMax, s.freqLinear, 42f, (viewWidth - 42f).coerceAtLeast(1f))
        .coerceIn(10.0, 24000.0)
    return if (which == 0) s.copy(cursorA = freq) else s.copy(cursorB = freq)
}

internal fun nearestCursor(s: UiSettings, x: Float, viewWidth: Float): Int {
    val width = (viewWidth - 42f).coerceAtLeast(1f)
    val a = xOf(s.cursorA, s.freqMin, s.freqMax, s.freqLinear, 42f, width)
    val b = xOf(s.cursorB, s.freqMin, s.freqMax, s.freqLinear, 42f, width)
    return if (abs(x - a) <= abs(x - b)) 0 else 1
}

private fun clampFreq(min: Double, max: Double): Pair<Double, Double> {
    var a = min
    var b = max
    if (b < a) {
        val t = a
        a = b
        b = t
    }
    if (b < a * 1.12) b = a * 1.12
    if (a < 10.0) {
        val ratio = b / a.coerceAtLeast(1e-6)
        a = 10.0
        b = a * ratio
    }
    if (b > 24000.0) {
        val ratio = b / a
        b = 24000.0
        a = (b / ratio).coerceAtLeast(10.0)
    }
    return a to b
}
