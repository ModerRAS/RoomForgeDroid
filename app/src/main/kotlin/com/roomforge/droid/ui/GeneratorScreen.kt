/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.core.Waveform
import com.roomforge.droid.LiveReadout
import com.roomforge.droid.UiSettings
import com.roomforge.droid.audio.Session
import kotlin.math.pow

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GeneratorScreen(session: Session, settings: UiSettings, live: LiveReadout) {
    var sweep by remember { mutableStateOf<Waveform?>(null) }
    var askHz by remember { mutableStateOf(false) }
    var askImpulse by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenBack("信号发生器", onBack = { session.setScreen(com.roomforge.droid.Screen.ANALYZER) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (settings.generatorOn) "输出开  ${waveLabel(settings.waveform)}" else "输出关",
                color = if (settings.generatorOn) Scope else Dim,
                fontSize = 18.sp
            )
            KeyButton(if (settings.generatorOn) "关闭" else "打开") {
                session.edit { it.copy(generatorOn = !it.generatorOn) }
            }
            Text("${hzLabel(settings.genHz)} Hz", color = Ink, fontSize = 32.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("<<") { session.edit { it.copy(genHz = (it.genHz / 2.0).coerceIn(1.0, 20000.0)) } }
                KeyButton("<") { session.edit { it.copy(genHz = (it.genHz / 2.0.pow(1.0 / 12.0)).coerceIn(1.0, 20000.0)) } }
                KeyButton(">") { session.edit { it.copy(genHz = (it.genHz * 2.0.pow(1.0 / 12.0)).coerceIn(1.0, 20000.0)) } }
                KeyButton(">>") { session.edit { it.copy(genHz = (it.genHz * 2.0).coerceIn(1.0, 20000.0)) } }
                KeyButton("频率") { askHz = true }
            }
            Text("<< >> 八度，< > 半音。扫频和啭音使用下面的起止频率。", color = Dim, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Waveform.entries.forEach { wave ->
                    KeyButton(waveLabel(wave)) { session.edit { it.copy(waveform = wave, generatorOn = true) } }
                }
            }
            Text("音量 ${(settings.volume * 100).toInt()}%", color = Ink)
            Slider(
                value = settings.volume,
                onValueChange = { v -> session.edit(save = false) { it.copy(volume = v) } },
                onValueChangeFinished = { session.persist() },
                valueRange = 0f..1f
            )
            if (settings.volume > 0.5f) Text("音量较高，先把手机音量调低。", color = Target, fontSize = 12.sp)
            RowChecks(settings, session)
            Text(
                "扫频 ${hzLabel(settings.sweepStart)} → ${hzLabel(settings.sweepEnd)} Hz，${"%.1f".format(settings.sweepDur)} s。啭音在一半时间内上升，再下降。",
                color = Dim,
                fontSize = 12.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("线性扫频") { sweep = Waveform.SWEEP }
                KeyButton("对数扫频") { sweep = Waveform.LOG_SWEEP }
                KeyButton("啭音") { sweep = Waveform.WARBLE }
            }
            Text("脉冲间隔 ${"%.2f".format(settings.impulseSec)} s", color = Ink)
            Slider(
                value = settings.impulseSec.toFloat(),
                onValueChange = { v -> session.edit(save = false) { it.copy(impulseSec = v.toDouble()) } },
                onValueChangeFinished = { session.persist() },
                valueRange = 0.05f..5f
            )
            KeyButton("设置脉冲间隔") { askImpulse = true }
            if (settings.waveform == Waveform.POLARITY) {
                Text(live.polarity ?: "等待极性脉冲…", color = Bar, fontSize = 20.sp)
                Text("正极性半正弦，每 0.5 s 一次。Pol +++ 同相，Pol --- 反相。", color = Dim, fontSize = 12.sp)
            }
            Text(
                "粉噪在 1/3 RTA 上应接近平直；白噪在倍频程上大约每倍频程 +3 dB，在线性 FFT 上才接近平直。可以和峰值存储同时使用。",
                color = Dim,
                fontSize = 12.sp
            )
        }
    }
    val pending = sweep
    if (pending != null) {
        NumberDialog(
            title = waveLabel(pending),
            hint = "起始 Hz、终止 Hz、时长秒。到达终点后循环。",
            labels = listOf("起始", "终止", "时长"),
            initial = listOf(settings.sweepStart.toString(), settings.sweepEnd.toString(), settings.sweepDur.toString()),
            onDismiss = { sweep = null },
            onConfirm = { n ->
                session.edit {
                    it.copy(
                        waveform = pending,
                        generatorOn = true,
                        sweepStart = n[0].coerceIn(1.0, 20000.0),
                        sweepEnd = n[1].coerceIn(1.0, 20000.0),
                        sweepDur = n[2].coerceIn(0.05, 120.0)
                    )
                }
                sweep = null
            }
        )
    }
    if (askHz) {
        NumberDialog(
            title = "频率",
            hint = "",
            labels = listOf("Hz"),
            initial = listOf(settings.genHz.toString()),
            onDismiss = { askHz = false },
            onConfirm = { n ->
                session.edit { it.copy(genHz = n[0].coerceIn(1.0, 20000.0)) }
                askHz = false
            }
        )
    }
    if (askImpulse) {
        NumberDialog(
            title = "脉冲间隔",
            hint = "0.05 到 5 秒",
            labels = listOf("秒"),
            initial = listOf(settings.impulseSec.toString()),
            onDismiss = { askImpulse = false },
            onConfirm = { n ->
                session.edit { it.copy(impulseSec = n[0].coerceIn(0.05, 5.0), waveform = Waveform.IMPULSE) }
                askImpulse = false
            }
        )
    }
}

@Composable
private fun RowChecks(settings: UiSettings, session: Session) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = settings.leftOn, onCheckedChange = { v -> session.edit { it.copy(leftOn = v) } })
        Text("L", color = Ink)
        Checkbox(checked = settings.rightOn, onCheckedChange = { v -> session.edit { it.copy(rightOn = v) } })
        Text("R", color = Ink)
        Checkbox(checked = settings.invertRight, onCheckedChange = { v -> session.edit { it.copy(invertRight = v) } })
        Text("右声道反相", color = Ink, fontSize = 13.sp)
    }
}
