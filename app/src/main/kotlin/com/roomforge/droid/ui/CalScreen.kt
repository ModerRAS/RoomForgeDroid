/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.core.CalMode
import com.roomforge.core.IsoBands
import com.roomforge.droid.Screen
import com.roomforge.droid.UiSettings
import com.roomforge.droid.audio.Session

@Composable
internal fun CalScreen(session: Session, settings: UiSettings, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenBack("校准", onBack = { session.finishCal() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text(
                if (settings.calMode == CalMode.SINGLE) "方式：单一偏移" else "方式：1/3 倍频程 + 全局偏移",
                color = Bar
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("单一") { session.edit { it.copy(calMode = CalMode.SINGLE) } }
                KeyButton("1/3") { session.edit { it.copy(calMode = CalMode.THIRD_OCTAVE) } }
            }
            val offset = if (settings.calMode == CalMode.SINGLE) settings.singleOffset else settings.thirdOffset
            Text("全局 O= ${"%.1f".format(offset)} dB", color = Ink, modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = offset.toFloat(),
                onValueChange = { v ->
                    session.edit(save = false) { s ->
                        if (s.calMode == CalMode.SINGLE) s.copy(singleOffset = v.toDouble())
                        else s.copy(thirdOffset = v.toDouble())
                    }
                },
                onValueChangeFinished = { session.persist() },
                valueRange = -40f..40f
            )
            Text(
                "圆盘是 31 个 1/3 倍频程修正。双击左右边缘切换频带，上下拖动改 dB。0 dB 在中线。全局偏移加在圆盘之上，宽带 SPL 只用全局偏移。",
                color = Dim,
                fontSize = 12.sp
            )
            DiscEditor(
                values = settings.thirdBands,
                min = -20.0,
                max = 20.0,
                baseline = 0.0,
                onChange = { i, v -> session.setBand(i, v, target = false, save = false) },
                onCommit = { session.persist() }
            )
            settings.sensDb?.let {
                Text("文件灵敏度因子 $it dB。它不会自动当声级计用。", color = Dim, fontSize = 12.sp)
                KeyButton("把灵敏度加到 O=") { session.applySens() }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("归零") { session.zeroBands(false) }
                KeyButton("反向") { session.invertCal() }
                KeyButton("导入", onClick = onImport)
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("完成") { session.finishCal() }
                KeyButton("放弃") { session.revertCal() }
            }
        }
    }
}

@Composable
internal fun TargetScreen(session: Session, settings: UiSettings, onSave: () -> Unit, onLoad: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenBack("目标曲线", onBack = { session.finishTarget() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text("默认约 60 dB 平直。显示在 RTA 上为红色。", color = Dim, fontSize = 12.sp)
            DiscEditor(
                values = settings.target,
                min = 0.0,
                max = 120.0,
                baseline = 60.0,
                onChange = { i, v -> session.setBand(i, v, target = true, save = false) },
                onCommit = { session.persist() }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("恢复 60 dB") { session.zeroBands(true) }
                KeyButton("保存", onClick = onSave)
                KeyButton("载入", onClick = onLoad)
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton(if (settings.showTarget) "隐藏" else "显示") {
                    session.edit { it.copy(showTarget = !it.showTarget) }
                }
                KeyButton("完成") { session.finishTarget() }
                KeyButton("放弃") { session.revertTarget() }
            }
        }
    }
}

@Composable
private fun DiscEditor(
    values: List<Double>,
    min: Double,
    max: Double,
    baseline: Double,
    onChange: (Int, Double) -> Unit,
    onCommit: () -> Unit
) {
    var selected by remember { mutableIntStateOf(16) }
    val currentValues by rememberUpdatedState(values)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val shown = currentValues.getOrElse(selected) { baseline }
    val center = IsoBands.octave3.getOrNull(selected)?.centerHz ?: 1000.0
    Text("${hzLabel(center)} Hz   ${"%.1f".format(shown)} dB", color = Ink, fontSize = 16.sp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(min, max) {
                detectTapGestures(onDoubleTap = { pos ->
                    val edge = size.width * 0.16f
                    selected = when {
                        pos.x < edge -> (selected - 1).floorMod(31)
                        pos.x > size.width - edge -> (selected + 1).floorMod(31)
                        else -> (pos.x / size.width * 31).toInt().coerceIn(0, 30)
                    }
                })
            }
            .pointerInput(min, max) {
                detectVerticalDragGestures(
                    onDragStart = { pos ->
                        selected = (pos.x / size.width * 31).toInt().coerceIn(0, 30)
                    },
                    onVerticalDrag = { pointer, drag ->
                        val idx = (pointer.position.x / size.width * 31).toInt().coerceIn(0, 30)
                        selected = idx
                        val current = currentValues.getOrElse(idx) { baseline }
                        val next = (current - drag / size.height * (max - min)).coerceIn(min, max)
                        change(idx, next)
                    },
                    onDragEnd = { commit() },
                    onDragCancel = { commit() }
                )
            }
    ) {
        val n = 31
        val gap = 2f
        val bw = ((size.width - gap * (n + 1)) / n).coerceAtLeast(2f)
        val baseY = yDisc(baseline, min, max, size.height)
        drawLine(Grid, Offset(0f, baseY), Offset(size.width, baseY), 1f)
        for (i in 0 until n) {
            val v = values.getOrElse(i) { baseline }.coerceIn(min, max)
            val y = yDisc(v, min, max, size.height)
            val top = minOf(y, baseY)
            val x = gap + i * (bw + gap)
            drawRoundRect(
                color = if (i == selected) Bar else Bar.copy(alpha = 0.45f),
                topLeft = Offset(x, top),
                size = Size(bw, abs(baseY - y).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

private fun yDisc(value: Double, min: Double, max: Double, height: Float): Float {
    val t = ((value - min) / (max - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
    return ((1.0 - t) * height).toFloat()
}

private fun Int.floorMod(n: Int): Int = ((this % n) + n) % n

private fun abs(v: Float) = kotlin.math.abs(v)
