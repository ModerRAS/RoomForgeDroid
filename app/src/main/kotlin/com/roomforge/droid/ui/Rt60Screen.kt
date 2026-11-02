/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.core.DecayMetrics
import com.roomforge.core.Rt60Phase
import com.roomforge.droid.Rt60Ui
import com.roomforge.droid.Screen
import com.roomforge.droid.UiSettings
import com.roomforge.droid.audio.Session

@Composable
internal fun Rt60Screen(session: Session, settings: UiSettings, rt60: Rt60Ui, onStore: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenBack("RT60", onBack = { session.setScreen(Screen.ANALYZER) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(phaseLabel(rt60.phase), color = Bar, fontSize = 20.sp)
            Text("瞬时 ${dbLabel(rt60.liveDb)} dB（不计权，仅用于触发）", color = Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(if (settings.generatorOn) "触发" else "等待拍手") {
                    if (settings.generatorOn) session.triggerRt60() else session.armRt60()
                }
                KeyButton("触发并停发生器") { session.triggerRt60() }
                KeyButton("重新等待") { session.armRt60() }
            }
            Text(
                "发生器关闭时拍手触发。发生器开着时点触发：先停发生器，再测衰减。RT Early = 峰值到 -10 dB 的时间 × 6。15–20 = -5 dB 到 -20 dB 的时间 × 4。Schroeder 反向积分。",
                color = Dim,
                fontSize = 12.sp
            )
            KeyButton(if (settings.rt60Table) "隐藏表格" else "显示表格") {
                session.edit { it.copy(rt60Table = !it.rt60Table) }
            }
            if (settings.rt60Table) {
                val report = rt60.report
                if (report == null) {
                    Text("还没有结果。", color = Ink)
                } else {
                    Header()
                    MetricRow("宽带", report.wideband, null)
                    report.bands.forEach { (hz, metrics) ->
                        val loaded = session.loadedRt60?.firstOrNull { row ->
                            row.name == hzLabel(hz) || row.name == hz.toString() || row.name.startsWith(hzLabel(hz))
                        }
                        MetricRow(hzLabel(hz), metrics, loaded?.let { it.earlySec to it.t15to20Sec })
                    }
                    Text("灰色对照来自已加载的 .rt60。动态范围不够时 15–20 为空。", color = Dim, fontSize = 11.sp)
                }
            }
            KeyButton("存储 .rt60", onClick = onStore)
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("频带", color = Dim, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text("Early", color = Dim, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text("15–20", color = Dim, modifier = Modifier.weight(1f), fontSize = 12.sp)
    }
}

@Composable
private fun MetricRow(name: String, metrics: DecayMetrics, compare: Pair<Double, Double>?) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, color = Ink, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(sec(metrics.earlySec), color = Ink, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(sec(metrics.t15to20Sec), color = Ink, modifier = Modifier.weight(1f), fontSize = 13.sp)
    }
    if (compare != null) {
        Row(Modifier.fillMaxWidth()) {
            Text("文件", color = LoadedA, modifier = Modifier.weight(1f), fontSize = 11.sp)
            Text(sec(compare.first), color = LoadedA, modifier = Modifier.weight(1f), fontSize = 11.sp)
            Text(sec(compare.second), color = LoadedA, modifier = Modifier.weight(1f), fontSize = 11.sp)
        }
    }
}

private fun sec(value: Double): String = if (value.isFinite()) "%.2f s".format(value) else "—"

private fun phaseLabel(phase: Rt60Phase): String = when (phase) {
    Rt60Phase.IDLE -> "空闲"
    Rt60Phase.WAITING -> "等待拍手"
    Rt60Phase.RECORDING -> "记录衰减"
    Rt60Phase.READY -> "完成"
}
