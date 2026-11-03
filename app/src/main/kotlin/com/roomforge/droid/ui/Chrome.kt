/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.core.RtaMode
import com.roomforge.core.TimeConstant
import com.roomforge.core.Waveform
import com.roomforge.core.Weighting
import com.roomforge.droid.UpdateState
import com.roomforge.droid.audio.Session
import kotlin.math.abs
import kotlin.math.log10

internal val Bg = Color(0xFF0E1418)
internal val Ink = Color(0xFFE7E1D6)
internal val Dim = Color(0xFF8C98A0)
internal val Bar = Color(0xFFE6B35A)
internal val Peak = Color(0xFF7EB6FF)
internal val Valley = Color(0xFFF4F7FA)
internal val Target = Color(0xFFFF5A5A)
internal val Scope = Color(0xFF3DDC97)
internal val Grid = Color(0xFF243038)
internal val LoadedA = Color(0xFF9AA4AD)
internal val LoadedB = Color(0xFF6AA8FF)
internal val Panel = Color(0xFF152026)

internal fun roomForgeColors() = darkColorScheme(
    background = Bg,
    surface = Panel,
    primary = Bar,
    onPrimary = Color(0xFF1A1204),
    secondary = Scope,
    onSurface = Ink,
    onBackground = Ink
)

@Composable
internal fun RoomForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = roomForgeColors(), content = content)
}

@Composable
internal fun KeyButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        color = Ink,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .border(1.dp, Bar, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Bar, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}

internal fun modeLabel(mode: RtaMode): String = when (mode) {
    RtaMode.OCTAVE_1 -> "1/1"
    RtaMode.OCTAVE_3 -> "1/3"
    RtaMode.OCTAVE_6 -> "1/6"
    RtaMode.OCTAVE_12 -> "1/12"
    RtaMode.FULL -> "FFT"
    RtaMode.SPECTROGRAM -> "频谱图"
    RtaMode.WATERFALL -> "瀑布图"
    RtaMode.SPL -> "SPL"
    RtaMode.LEQ -> "Leq"
    RtaMode.CHART -> "记录"
    RtaMode.PEAK_STORE -> "峰值"
}

internal fun speedLabel(speed: TimeConstant): String = when (speed) {
    TimeConstant.FAST -> "Fast"
    TimeConstant.MEDIUM -> "Medium"
    TimeConstant.SLOW -> "Slow"
    TimeConstant.IMPULSE -> "Impulse"
}

internal fun weightLabel(weighting: Weighting): String = when (weighting) {
    Weighting.FLAT -> "Flat"
    Weighting.A -> "A"
    Weighting.C -> "C"
    Weighting.X -> "X"
}

internal fun waveLabel(wave: Waveform): String = when (wave) {
    Waveform.SINE -> "正弦"
    Waveform.SQUARE -> "方波"
    Waveform.TRIANGLE -> "三角"
    Waveform.RAMP -> "锯齿"
    Waveform.IMPULSE -> "脉冲"
    Waveform.SILENCE -> "静音"
    Waveform.WHITE -> "白噪"
    Waveform.PINK -> "粉噪"
    Waveform.SWEEP -> "线性扫频"
    Waveform.LOG_SWEEP -> "对数扫频"
    Waveform.WARBLE -> "啭音"
    Waveform.POLARITY -> "极性"
}

internal fun hzLabel(freq: Double): String = when {
    !freq.isFinite() || freq <= 0.0 -> "—"
    freq >= 1000.0 -> {
        val k = freq / 1000.0
        if (abs(k - k.toInt()) < 0.05) "${k.toInt()}k" else "%.1fk".format(k)
    }
    freq >= 100.0 -> freq.toInt().toString()
    abs(freq - freq.toInt()) < 0.05 -> freq.toInt().toString()
    else -> "%.1f".format(freq)
}

internal fun dbLabel(db: Double): String = if (!db.isFinite()) "—" else "%.1f".format(db)

internal fun dbfs(peak: Double): Double = 20.0 * log10(peak.coerceAtLeast(1e-12))

@Composable
internal fun NumberDialog(
    title: String,
    hint: String,
    labels: List<String>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Double>) -> Unit
) {
    val fields = remember(title) { initial.map { mutableStateOf(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(title, color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint.isNotEmpty()) Text(hint, color = Dim, fontSize = 12.sp)
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = fields[i].value,
                        onValueChange = { fields[i].value = it },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nums = fields.map { it.value.toDoubleOrNull() }
                if (nums.all { it != null }) onConfirm(nums.map { it!! })
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun NameDialog(
    title: String,
    hint: String,
    initial: String,
    confirm: String,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit
) {
    var text by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDiscard,
        containerColor = Panel,
        title = { Text(title, color = Ink) },
        text = {
            Column {
                if (hint.isNotEmpty()) Text(hint, color = Dim, fontSize = 12.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("放弃") } }
    )
}

@Composable
internal fun ChoiceDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(title, color = Ink) },
        text = {
            Column {
                options.forEach { (label, action) ->
                    Text(
                        label,
                        color = Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                action()
                                onDismiss()
                            }
                            .padding(vertical = 10.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
internal fun NoticeDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(title, color = Ink) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(body, color = Ink, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable
internal fun UpdateDialog(update: UpdateState, session: Session) {
    when (update) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { session.dismissUpdate() },
            containerColor = Panel,
            title = { Text("发现新版本 ${update.info.version}", color = Ink) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(update.info.notes, color = Ink, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { session.downloadUpdate() }) { Text("下载") } },
            dismissButton = { TextButton(onClick = { session.dismissUpdate() }) { Text("稍后") } }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            containerColor = Panel,
            title = { Text("下载中", color = Ink) },
            text = {
                Text(
                    if (update.total > 0) "${(update.done * 100 / update.total).toInt()}%（${update.done / 1024} / ${update.total / 1024} KB）"
                    else "已下 ${update.done / 1024} KB",
                    color = Ink
                )
            },
            confirmButton = { TextButton(onClick = { session.dismissUpdate() }) { Text("后台下") } }
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = { session.dismissUpdate() },
            containerColor = Panel,
            title = { Text("下载完成", color = Ink) },
            text = { Text("点安装调起系统安装器。覆盖安装要求签名一致；签不一致需先卸载旧版。", color = Ink, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { session.installUpdate() }) { Text("安装") } },
            dismissButton = { TextButton(onClick = { session.dismissUpdate() }) { Text("稍后") } }
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { session.dismissUpdate() },
            containerColor = Panel,
            title = { Text("更新失败", color = Ink) },
            text = { Text(update.msg, color = Ink, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { session.dismissUpdate() }) { Text("知道了") } }
        )
        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { session.dismissUpdate() },
            containerColor = Panel,
            title = { Text("已是最新", color = Ink) },
            text = { Text("当前已是 GitHub 上的最新版本。", color = Ink, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { session.dismissUpdate() }) { Text("知道了") } }
        )
        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}

@Composable
internal fun MenuLine(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Bar, fontSize = 13.sp, maxLines = 1)
    }
}

internal fun heatColor(db: Double, dbMin: Double, dbMax: Double): Int {
    val t = ((db - dbMin) / (dbMax - dbMin).coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat()
    val r: Int
    val g: Int
    val b: Int
    when {
        t < 0.25f -> {
            val u = t / 0.25f
            r = 8
            g = (30 + 40 * u).toInt()
            b = (70 + 110 * u).toInt()
        }
        t < 0.5f -> {
            val u = (t - 0.25f) / 0.25f
            r = (20 + 40 * u).toInt()
            g = (70 + 130 * u).toInt()
            b = (180 * (1f - u)).toInt()
        }
        t < 0.75f -> {
            val u = (t - 0.5f) / 0.25f
            r = (60 + 170 * u).toInt()
            g = (180 + 30 * u).toInt()
            b = (20 * u).toInt()
        }
        else -> {
            val u = (t - 0.75f) / 0.25f
            r = 230
            g = (210 + 40 * u).toInt()
            b = (40 + 200 * u).toInt()
        }
    }
    return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

@Composable
internal fun ScreenBack(title: String, onBack: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeyButton("返回", onClick = onBack)
        Text(title, color = Ink, modifier = Modifier.padding(start = 10.dp).weight(1f))
        extra()
    }
}
