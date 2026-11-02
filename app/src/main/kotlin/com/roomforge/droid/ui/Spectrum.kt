/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.roomforge.core.Curve
import com.roomforge.core.DisplayFrame
import com.roomforge.core.IsoBands
import com.roomforge.core.Music
import com.roomforge.core.NoiseCurves
import com.roomforge.core.RtaMode
import com.roomforge.core.Sones
import com.roomforge.core.freqAtCursor
import com.roomforge.core.nearestBandDb
import com.roomforge.droid.UiSettings
import com.roomforge.droid.audio.Session
import com.roomforge.droid.audio.SpecBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

private const val LEFT = 42f
private const val BOTTOM = 18f

internal fun Modifier.plotGestures(session: Session): Modifier = pointerInput(session) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var last = down.position
        var span = 0f
        var moved = false
        var zooming = false
        var grabbed = -1
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                zooming = true
                moved = true
                val c = Offset(
                    (pressed[0].position.x + pressed[1].position.x) / 2f,
                    (pressed[0].position.y + pressed[1].position.y) / 2f
                )
                val now = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(8f)
                if (span > 0f && !session.settings.value.showCursors) {
                    session.edit(save = false) {
                        scaleSettings(it, c - last, now / span, size.width.toFloat(), size.height.toFloat(), c)
                    }
                }
                last = c
                span = now
                pressed.forEach { it.consume() }
            } else if (!zooming) {
                val p = pressed[0]
                val delta = p.position - last
                if (moved || delta.getDistance() > slop) {
                    moved = true
                    val width = size.width.toFloat()
                    val s = session.settings.value
                    if (s.showCursors) {
                        if (grabbed < 0) grabbed = nearestCursor(s, p.position.x, width)
                        session.edit(save = false) { moveCursor(it, p.position.x, width, grabbed) }
                    } else if (abs(p.positionChange().x) + abs(p.positionChange().y) > 0f) {
                        session.edit(save = false) {
                            scaleSettings(it, delta, 1f, width, size.height.toFloat(), p.position)
                        }
                    }
                    p.consume()
                }
                last = p.position
            }
        }
        if (!moved) {
            session.edit { it.copy(buttonsVisible = !it.buttonsVisible) }
        } else {
            session.persist()
        }
    }
}

@Composable
internal fun SpectrumPlot(session: Session, settings: UiSettings, frame: DisplayFrame?) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Dim, fontSize = 10.sp)
    val ink = TextStyle(color = Ink, fontSize = 11.sp)
    Canvas(Modifier.fillMaxSize().plotGestures(session)) {
        drawRect(Bg)
        val plotW = (size.width - LEFT).coerceAtLeast(1f)
        val plotH = (size.height - BOTTOM).coerceAtLeast(1f)
        drawGrid(settings, plotW, plotH, measurer, style)
        if (frame == null) {
            drawText(measurer, "等待采样…", Offset(LEFT, 8f), ink)
            return@Canvas
        }
        val curve = curveFor(settings.mode, frame)
        if (settings.mode == RtaMode.FULL) {
            drawLineCurve(curve, Bar, settings, plotW, plotH, 1.6f)
            if (settings.peaks) drawLineCurve(peakCurve(curve), Peak, settings, plotW, plotH, 1f)
            if (settings.valleys) drawLineCurve(valleyCurve(curve), Valley, settings, plotW, plotH, 1f)
        } else {
            drawBars(curve, settings, plotW, plotH)
        }
        if (settings.showTarget && settings.target.size >= 31 &&
            settings.mode != RtaMode.SPL && settings.mode != RtaMode.LEQ && settings.mode != RtaMode.CHART
        ) {
            drawTarget(settings, plotW, plotH)
        }
        if (settings.mode == RtaMode.OCTAVE_1 && settings.showNc) drawNc(settings, plotW, plotH)
        if (settings.mode == RtaMode.OCTAVE_1 && settings.showNr) drawNr(settings, plotW, plotH)
        session.loadedA?.let { drawLineCurve(it, LoadedA, settings, plotW, plotH, 1.2f) }
        session.loadedB?.let { drawLineCurve(it, LoadedB, settings, plotW, plotH, 1.2f) }
        if (settings.mode == RtaMode.PEAK_STORE) {
            session.loadedSwp?.let { swp ->
                val overlay = Curve(swp.freqHz, swp.peakDb, swp.peakDb, swp.peakDb)
                drawLineCurve(overlay, LoadedA, settings, plotW, plotH, 1.4f)
            }
        }
        if (settings.showCursors) {
            drawCursor(settings.cursorA, Peak, settings, plotW, plotH)
            drawCursor(settings.cursorB, Target, settings, plotW, plotH)
        }
        drawHud(settings, frame, curve, measurer, ink, style)
    }
}

@Composable
internal fun HeatPlot(session: Session, settings: UiSettings, buffer: SpecBuffer) {
    val w = buffer.width
    val h = buffer.height
    val pixels = remember(w, h) { IntArray(w * h) }
    val bitmap = remember(w, h) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Dim, fontSize = 10.sp)
    Canvas(Modifier.fillMaxSize().plotGestures(session)) {
        drawRect(Bg)
        paintHeat(pixels, buffer, settings.dbMin, settings.dbMax)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val plotW = (size.width - LEFT).coerceAtLeast(1f)
        val plotH = (size.height - BOTTOM).coerceAtLeast(1f)
        val x0 = logBin(settings.freqMin, w)
        val x1 = logBin(settings.freqMax, w).coerceAtLeast(x0 + 1)
        drawImage(
            bitmap.asImageBitmap(),
            srcOffset = IntOffset(x0, 0),
            srcSize = IntSize(x1 - x0, h),
            dstOffset = IntOffset(LEFT.toInt(), 0),
            dstSize = IntSize(plotW.toInt().coerceAtLeast(1), plotH.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None
        )
        drawGrid(settings, plotW, plotH, measurer, style)
        if (settings.showCursors) {
            drawCursor(settings.cursorA, Peak, settings, plotW, plotH)
            drawCursor(settings.cursorB, Target, settings, plotW, plotH)
        }
        val caption = if (settings.mode == RtaMode.WATERFALL) "瀑布图 · 对数频率 20 Hz–20 kHz" else {
            if (settings.spectrogramFast) "频谱图 Fast" else "频谱图 Slow"
        }
        drawText(measurer, caption, Offset(LEFT, 4f), style)
    }
}

@Composable
internal fun ChartPlot(session: Session, settings: UiSettings, frame: DisplayFrame?) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Dim, fontSize = 10.sp)
    Canvas(Modifier.fillMaxSize().plotGestures(session)) {
        drawRect(Bg)
        val plotW = (size.width - LEFT).coerceAtLeast(1f)
        val plotH = (size.height - BOTTOM).coerceAtLeast(1f)
        val chart = frame?.chart
        if (chart == null || chart.splDb.isEmpty()) {
            drawText(measurer, "记录图还没有点", Offset(LEFT, 12f), TextStyle(color = Ink, fontSize = 12.sp))
            return@Canvas
        }
        val span = if (settings.chartSpan > 0.5) settings.chartSpan else chart.secondsAgo.maxOrNull() ?: 1.0
        fun xAgo(ago: Double) = LEFT + plotW * (1f - (ago / span).coerceIn(0.0, 1.0).toFloat())
        val path = Path()
        var started = false
        for (i in chart.splDb.indices) {
            val ago = chart.secondsAgo[i]
            if (ago > span) continue
            val p = Offset(xAgo(ago), yOf(chart.splDb[i], settings.dbMin, settings.dbMax, 0f, plotH))
            if (!started) {
                path.moveTo(p.x, p.y)
                started = true
            } else path.lineTo(p.x, p.y)
        }
        drawPath(path, Scope, style = Stroke(1.6f, cap = StrokeCap.Round))
        session.loadedChart?.let { samples ->
            if (samples.size >= 2) {
                val t0 = samples.first().timeSec
                val t1 = samples.last().timeSec
                val dur = (t1 - t0).coerceAtLeast(1e-3)
                val loaded = Path()
                samples.forEachIndexed { i, s ->
                    val x = LEFT + plotW * ((s.timeSec - t0) / dur).toFloat()
                    val y = yOf(s.splDb, settings.dbMin, settings.dbMax, 0f, plotH)
                    if (i == 0) loaded.moveTo(x, y) else loaded.lineTo(x, y)
                }
                drawPath(loaded, LoadedA, style = Stroke(1.2f))
            }
        }
        val last = chart.splDb.lastIndex
        val note = "SPL ${dbLabel(chart.splDb[last])}  峰值 ${hzLabel(chart.peakHz[last])} Hz" +
            (if (chart.sweepHz[last].isFinite()) "  扫频 ${hzLabel(chart.sweepHz[last])}" else "") +
            "  ${if (settings.chartSpan <= 0.5) "全部" else "${settings.chartSpan.toInt()} s"}"
        drawText(measurer, note, Offset(LEFT, 4f), style)
        drawText(measurer, "现在", Offset(size.width - 36f, plotH + 2f), style)
    }
}

private fun paintHeat(pixels: IntArray, buffer: SpecBuffer, dbMin: Double, dbMax: Double) {
    val newest = buffer.published
    val w = buffer.width
    val h = buffer.height
    if (newest < 0) {
        pixels.fill(0xFF0E1418.toInt())
        return
    }
    for (age in 0 until h) {
        val src = buffer.rows[(newest - age + h * 4) % h]
        val row = (h - 1 - age) * w
        for (x in 0 until w) pixels[row + x] = heatColor(src[x], dbMin, dbMax)
    }
}

private fun logBin(freq: Double, width: Int): Int {
    val t = (ln(freq.coerceIn(20.0, 20000.0)) - ln(20.0)) / (ln(20000.0) - ln(20.0))
    return (t * width).toInt().coerceIn(0, width - 1)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    settings: UiSettings,
    plotW: Float,
    plotH: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle
) {
    var db = kotlin.math.ceil(settings.dbMin / settings.dbStep) * settings.dbStep
    var guard = 0
    while (db <= settings.dbMax && guard++ < 40) {
        val y = yOf(db, settings.dbMin, settings.dbMax, 0f, plotH)
        drawLine(Grid, Offset(LEFT, y), Offset(LEFT + plotW, y), 1f)
        drawText(measurer, db.toInt().toString(), Offset(2f, (y - 6f).coerceAtLeast(0f)), style)
        db += settings.dbStep.coerceAtLeast(1.0)
    }
    val marks = if (settings.freqLinear) {
        doubleArrayOf(1000.0, 2000.0, 5000.0, 10000.0, 15000.0, 20000.0)
    } else {
        doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
    }
    for (f in marks) {
        if (f < settings.freqMin || f > settings.freqMax) continue
        val x = xOf(f, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
        drawLine(Grid, Offset(x, 0f), Offset(x, plotH), 1f)
        drawText(measurer, hzLabel(f), Offset(x + 2f, plotH + 2f), style)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
    curve: Curve,
    settings: UiSettings,
    plotW: Float,
    plotH: Float
) {
    val n = curve.size
    if (n == 0) return
    val bottom = yOf(settings.dbMin, settings.dbMin, settings.dbMax, 0f, plotH)
    for (i in 0 until n) {
        val f = curve.freqHz[i]
        val prev = when {
            i == 0 -> f / sqrt(2.0)
            else -> sqrt(curve.freqHz[i - 1] * f)
        }
        val next = when {
            i == n - 1 -> f * sqrt(2.0)
            else -> sqrt(f * curve.freqHz[i + 1])
        }
        val x0 = xOf(prev, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
        val x1 = xOf(next, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
        if (x1 < LEFT || x0 > LEFT + plotW) continue
        val y = yOf(curve.db[i], settings.dbMin, settings.dbMax, 0f, plotH)
        val top = y.coerceAtMost(bottom)
        drawRect(
            Bar,
            topLeft = Offset(x0 + 1f, top),
            size = androidx.compose.ui.geometry.Size((x1 - x0 - 2f).coerceAtLeast(1f), (bottom - top).coerceAtLeast(0f))
        )
        if (settings.peaks) {
            val py = yOf(curve.peakDb[i], settings.dbMin, settings.dbMax, 0f, plotH)
            drawLine(Peak, Offset(x0, py), Offset(x1, py), 2f)
        }
        if (settings.valleys) {
            val vy = yOf(curve.valleyDb[i], settings.dbMin, settings.dbMax, 0f, plotH)
            drawLine(Valley, Offset(x0, vy), Offset(x1, vy), 1.5f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineCurve(
    curve: Curve,
    color: androidx.compose.ui.graphics.Color,
    settings: UiSettings,
    plotW: Float,
    plotH: Float,
    stroke: Float
) {
    if (curve.size == 0) return
    val path = Path()
    var started = false
    for (i in 0 until curve.size) {
        val f = curve.freqHz[i]
        if (f <= 0.0 || !curve.db[i].isFinite()) continue
        val p = Offset(
            xOf(f, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW),
            yOf(curve.db[i], settings.dbMin, settings.dbMax, 0f, plotH)
        )
        if (!started) {
            path.moveTo(p.x, p.y)
            started = true
        } else path.lineTo(p.x, p.y)
    }
    if (started) drawPath(path, color, style = Stroke(stroke))
}

private fun peakCurve(curve: Curve) = Curve(curve.freqHz, curve.peakDb, curve.peakDb, curve.peakDb)
private fun valleyCurve(curve: Curve) = Curve(curve.freqHz, curve.valleyDb, curve.valleyDb, curve.valleyDb)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTarget(
    settings: UiSettings,
    plotW: Float,
    plotH: Float
) {
    val bands = IsoBands.octave3
    val path = Path()
    for (i in bands.indices) {
        val x = xOf(bands[i].centerHz, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
        val y = yOf(settings.target[i], settings.dbMin, settings.dbMax, 0f, plotH)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Target, style = Stroke(1.4f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNc(
    settings: UiSettings,
    plotW: Float,
    plotH: Float
) {
    for (nc in NoiseCurves.ncNumbers) {
        val levels = NoiseCurves.ncLevels(nc)
        val path = Path()
        for (i in levels.indices) {
            val x = xOf(NoiseCurves.ncFreq[i], settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
            val y = yOf(levels[i], settings.dbMin, settings.dbMax, 0f, plotH)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Valley.copy(alpha = 0.28f), style = Stroke(1f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNr(settings: UiSettings, plotW: Float, plotH: Float) {
    for (nr in intArrayOf(20, 30, 40, 50, 60)) {
        val path = Path()
        val freqs = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        freqs.forEachIndexed { i, f ->
            val x = xOf(f, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
            val y = yOf(NoiseCurves.nrLevel(nr, f), settings.dbMin, settings.dbMax, 0f, plotH)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Peak.copy(alpha = 0.25f), style = Stroke(1f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCursor(
    freq: Double,
    color: androidx.compose.ui.graphics.Color,
    settings: UiSettings,
    plotW: Float,
    plotH: Float
) {
    val x = xOf(freq, settings.freqMin, settings.freqMax, settings.freqLinear, LEFT, plotW)
    drawLine(color, Offset(x, 0f), Offset(x, plotH), 1.2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHud(
    settings: UiSettings,
    frame: DisplayFrame,
    curve: Curve,
    measurer: androidx.compose.ui.text.TextMeasurer,
    ink: TextStyle,
    dim: TextStyle
) {
    val note = if (settings.showNotes) Music.noteName(frame.maxFreqHz) else ""
    val at = levelAt(settings.mode, curve, frame.maxFreqHz)
    drawText(
        measurer,
        "${hzLabel(frame.maxFreqHz)} Hz  $note  ${dbLabel(at)} dB",
        Offset(LEFT + 4f, 2f),
        ink
    )
    var y = 16f
    if (frame.averagingCount > 0) {
        drawText(measurer, "平均 ${frame.averagingCount}", Offset(LEFT + 4f, y), dim)
        y += 13f
    }
    if (settings.showSone) {
        val sone = Sones.fromOctaveCurve(frame.oct1)
        drawText(measurer, "Sone ≈ %.2f（估计，建议 Flat）".format(sone), Offset(LEFT + 4f, y), dim)
        y += 13f
    }
    if (settings.mode == RtaMode.OCTAVE_1 && settings.showNc) {
        val nc = NoiseCurves.ncRating(frame.oct1.freqHz, frame.oct1.db)
        drawText(measurer, nc, Offset(LEFT + 4f, y), dim)
        y += 13f
    }
    if (settings.mode == RtaMode.OCTAVE_1 && settings.showNr) {
        val nr = NoiseCurves.nrRating(frame.oct1.freqHz, frame.oct1.db)
        drawText(measurer, nr, Offset(LEFT + 4f, y), dim)
        y += 13f
    }
    if (settings.showCursors) {
        val a = levelAt(settings.mode, curve, settings.cursorA)
        val b = levelAt(settings.mode, curve, settings.cursorB)
        val na = if (settings.showNotes) Music.noteName(settings.cursorA) else ""
        val nb = if (settings.showNotes) Music.noteName(settings.cursorB) else ""
        drawText(
            measurer,
            "A ${hzLabel(settings.cursorA)} $na ${dbLabel(a)}   B ${hzLabel(settings.cursorB)} $nb ${dbLabel(b)}   Δ ${dbLabel(b - a)} dB",
            Offset(LEFT + 4f, y),
            dim
        )
    }
    if (frame.toneHz.isFinite()) {
        drawText(measurer, "扫频 ${hzLabel(frame.toneHz)} Hz", Offset(size.width - 120f, 2f), dim)
    }
}

private fun levelAt(mode: RtaMode, curve: Curve, freq: Double): Double {
    if (curve.size == 0 || freq <= 0.0) return Double.NaN
    val bands = IsoBands.forMode(mode)
    return if (bands != null && bands.size == curve.size) nearestBandDb(freq, bands, curve.db)
    else freqAtCursor(freq, curve)
}

private fun curveFor(mode: RtaMode, frame: DisplayFrame): Curve = when (mode) {
    RtaMode.OCTAVE_1 -> frame.oct1
    RtaMode.OCTAVE_3 -> frame.oct3
    RtaMode.OCTAVE_6 -> frame.oct6
    RtaMode.OCTAVE_12 -> frame.oct12
    RtaMode.PEAK_STORE -> frame.peakStore
    else -> frame.full
}
