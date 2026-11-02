/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.core.DisplayFrame
import com.roomforge.core.IsoBands
import com.roomforge.core.RtaMode
import com.roomforge.droid.LiveReadout
import com.roomforge.droid.UiSettings
import com.roomforge.droid.audio.Session

@Composable
internal fun AnalyzerScreen(
    session: Session,
    settings: UiSettings,
    frame: DisplayFrame?,
    live: LiveReadout,
    onMenu: () -> Unit,
    onStore: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        MicLine(session, settings, live)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (settings.mode) {
                RtaMode.SPECTROGRAM -> HeatPlot(session, settings, session.spectrogram)
                RtaMode.WATERFALL -> HeatPlot(session, settings, session.waterfall)
                RtaMode.SPL -> SplPanel(session, settings, frame)
                RtaMode.LEQ -> LeqPanel(session, frame)
                RtaMode.CHART -> ChartPlot(session, settings, frame)
                else -> SpectrumPlot(session, settings, frame)
            }
        }
        if (!settings.hideInput) ScopeRow(frame, settings) else SplOnly(frame, settings)
        if (settings.buttonsVisible) {
            ControlRows(session, settings, onMenu, onStore)
            if (settings.showTransport) TransportRow(session, settings, live)
        }
        if (settings.status.isNotEmpty()) {
            Text(
                settings.status,
                color = Dim,
                fontSize = 11.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MicLine(session: Session, settings: UiSettings, live: LiveReadout) {
    val offset = settings.calibration().broadbandOffset()
    val pol = live.polarity?.let { "  $it" }.orEmpty()
    Text(
        "Mic: ${settings.micLabel}  ${settings.sampleRate}  ${"%.1f".format(live.frameMillis)}  ${settings.fftSize}  O=${"%.1f".format(offset)}$pol",
        color = Scope,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { session.edit { it.copy(buttonsVisible = !it.buttonsVisible) } }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ScopeRow(frame: DisplayFrame?, settings: UiSettings) {
    Row(
        Modifier.fillMaxWidth().height(78.dp).background(Panel),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(4.dp)) {
            val scope = frame?.scope ?: return@Canvas
            if (scope.isEmpty()) return@Canvas
            val mid = size.height / 2f
            var prev = Offset(0f, mid)
            for (i in scope.indices) {
                val x = i.toFloat() / scope.size.coerceAtLeast(1) * size.width
                val y = mid - scope[i].toFloat() * mid * 0.9f
                val p = Offset(x, y)
                if (i > 0) drawLine(Scope, prev, p, 1.4f)
                prev = p
            }
        }
        SplReadout(frame, settings)
    }
}

@Composable
private fun SplOnly(frame: DisplayFrame?, settings: UiSettings) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp)) {
        Text("输入已隐藏", color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(top = 8.dp))
        SplReadout(frame, settings)
    }
}

@Composable
private fun SplReadout(frame: DisplayFrame?, settings: UiSettings) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
        Text("SPL ${weightLabel(settings.weighting)}", color = Dim, fontSize = 10.sp)
        Text(if (frame == null) "—" else dbLabel(frame.splDb), color = Ink, fontSize = 26.sp)
        val peak = frame?.scopePeak
        Text(
            if (peak == null) "" else "峰 ${dbLabel(dbfs(peak))} dBFS",
            color = Dim,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SplPanel(session: Session, settings: UiSettings, frame: DisplayFrame?) {
    Column(
        Modifier
            .fillMaxSize()
            .clickable { session.edit { it.copy(buttonsVisible = !it.buttonsVisible) } }
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("SPL  ${weightLabel(settings.weighting)}", color = Bar, fontSize = 16.sp)
        Text(if (frame == null) "—" else dbLabel(frame.splDb), color = Ink, fontSize = 72.sp)
        Text("dB", color = Dim, fontSize = 18.sp)
        Text(
            "时间计权 ${speedLabel(settings.speed)}。这是分析声级，不是 IEC Class 1。未做声级计比对前，读数是 dBFS 加上 O= 偏移。",
            color = Dim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun LeqPanel(session: Session, frame: DisplayFrame?) {
    val leq = frame?.leq
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text("Leq  ${if (leq == null) "—" else "%.0f / %.0f s".format(leq.elapsedSec, leq.durationSec)}", color = Bar)
        Text(
            "Now  ${if (leq == null) "—" else dbLabel(leq.broadbandDb)} dB",
            color = Ink,
            fontSize = 28.sp
        )
        val recent = leq?.recent?.takeLast(8)?.asReversed().orEmpty()
        recent.forEachIndexed { i, rec ->
            Text("T-${i + 1}  ${dbLabel(rec.broadbandDb)} dB   ${"%.0f".format(rec.durationSec)} s", color = Dim, fontSize = 12.sp)
        }
        session.loadedLeq?.lastOrNull()?.let {
            Text("文件  ${dbLabel(it.broadbandDb)} dB", color = LoadedA, fontSize = 12.sp)
        }
        Text("倍频程 Leq", color = Bar, modifier = Modifier.padding(top = 8.dp))
        val oct = leq?.octaveDb
        IsoBands.octave1.forEachIndexed { i, band ->
            val v = oct?.getOrNull(i)
            Text("${hzLabel(band.centerHz)} Hz    ${if (v == null) "—" else dbLabel(v)}", color = Ink, fontSize = 13.sp)
        }
        Text(
            "宽带 Leq 用时间域能量；倍频程 Leq 用重叠 FFT 功率。改计权或从暂停回到运行会重新开始。历史最多 5000 条，这里显示最近 8 条。",
            color = Dim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ControlRows(session: Session, settings: UiSettings, onMenu: () -> Unit, onStore: () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.background(Panel).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton(modeLabel(settings.mode)) { session.cycleMode() }
            KeyButton(if (settings.running) "运行" else "暂停") { session.toggleRunning() }
            KeyButton(speedLabel(settings.speed)) { session.cycleSpeed() }
            KeyButton(weightLabel(settings.weighting)) { session.cycleWeight() }
            KeyButton(if (settings.averaging) "平均 开" else "平均 关") { session.toggleAveraging() }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton(if (settings.peaks) "Peak 开" else "Peak 关") { session.togglePeaks() }
            KeyButton(if (settings.valleys) "Valley 开" else "Valley 关") { session.toggleValleys() }
            KeyButton("存储", onClick = onStore)
            KeyButton("菜单", onClick = onMenu)
        }
    }
}

@Composable
internal fun TransportRow(session: Session, settings: UiSettings, live: LiveReadout) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(settings.wavName ?: "WAV", color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        KeyButton(if (settings.playing) "暂停" else "播放") { session.togglePlay() }
        KeyButton(if (settings.recording) "停 ${"%.0f".format(live.recordSeconds)}s" else "录音") { session.toggleRecord() }
        KeyButton("倒回") { session.rewind() }
    }
}
