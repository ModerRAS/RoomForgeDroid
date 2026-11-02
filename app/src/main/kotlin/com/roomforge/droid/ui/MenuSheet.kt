/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.core.CalMode
import com.roomforge.core.RtaMode
import com.roomforge.droid.Screen
import com.roomforge.droid.UiSettings
import androidx.compose.foundation.layout.padding
import com.roomforge.droid.audio.Session

@Composable
internal fun MenuSheet(session: Session, settings: UiSettings, actions: MenuActions) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenBack("菜单", onBack = actions.onClose)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MenuLine("信号发生器", if (settings.generatorOn) "开" else "关") {
                session.setScreen(Screen.GENERATOR)
                actions.onClose()
            }
            MenuLine("RT60", "") {
                session.setScreen(Screen.RT60)
                actions.onClose()
            }
            MenuLine("设置分辨率", settings.fftSize.toString(), actions.onResolution)
            MenuLine("麦克风", settings.micLabel, actions.onMics)
            MenuLine(if (settings.hideInput) "显示输入" else "隐藏输入", "") {
                session.edit { it.copy(hideInput = !it.hideInput) }
            }
            MenuLine(if (settings.showTransport) "隐藏录音播放" else "显示录音播放", "") {
                session.edit { it.copy(showTransport = !it.showTransport) }
            }
            MenuLine(if (settings.freqLinear) "改为对数 Hz" else "改为线性 Hz", "") {
                session.edit { it.copy(freqLinear = !it.freqLinear) }
            }
            MenuLine("设置频率刻度", "${hzLabel(settings.freqMin)}–${hzLabel(settings.freqMax)}", actions.onFreqScale)
            MenuLine("设置 dB 刻度", "${settings.dbMin.toInt()}…${settings.dbMax.toInt()} / ${settings.dbStep.toInt()}", actions.onDbScale)
            MenuLine("光标", if (settings.showCursors) "开" else "关") {
                session.edit { it.copy(showCursors = !it.showCursors) }
            }
            MenuLine("音名", if (settings.showNotes) "开" else "关") {
                session.edit { it.copy(showNotes = !it.showNotes) }
            }
            MenuLine("校准方式", if (settings.calMode == CalMode.SINGLE) "单一" else "1/3") {
                session.edit { s ->
                    s.copy(calMode = if (s.calMode == CalMode.SINGLE) CalMode.THIRD_OCTAVE else CalMode.SINGLE)
                }
            }
            MenuLine("校准", "O=%.1f".format(settings.calibration().broadbandOffset())) {
                session.openCal()
                actions.onClose()
            }
            MenuLine("设置校准偏移", "", actions.onOffset)
            MenuLine("导入校准", "Dayton / PMIK", actions.onImportCal)
            MenuLine("载入校准文件", "", actions.onPickCal)
            MenuLine("设置目标曲线", "") {
                session.openTarget()
                actions.onClose()
            }
            MenuLine(if (settings.showTarget) "隐藏目标" else "显示目标", "") {
                session.edit { it.copy(showTarget = !it.showTarget) }
            }
            MenuLine("保存目标", "", actions.onSaveTarget)
            MenuLine("载入目标", "", actions.onPickTarget)
            MenuLine("Sone", if (settings.showSone) "开" else "关") {
                session.edit { it.copy(showSone = !it.showSone) }
            }
            MenuLine("NC", if (settings.showNc) "开" else "关") {
                session.edit { it.copy(showNc = !it.showNc, mode = RtaMode.OCTAVE_1) }
            }
            MenuLine("NR", if (settings.showNr) "开" else "关") {
                session.edit { it.copy(showNr = !it.showNr, mode = RtaMode.OCTAVE_1) }
            }
            MenuLine("频谱图速度", if (settings.spectrogramFast) "Fast" else "Slow") {
                session.edit { it.copy(spectrogramFast = !it.spectrogramFast) }
            }
            MenuLine("瀑布图代替频谱图", if (settings.waterfall) "开" else "关") {
                session.toggleWaterfall()
            }
            MenuLine("Leq 时长", "${settings.leqDuration.toInt()} s", actions.onLeq)
            MenuLine("重新开始 Leq", "") { session.clearLeq() }
            MenuLine("记录图范围", if (settings.chartSpan <= 0.5) "全部" else "${settings.chartSpan.toInt()} s", actions.onChart)
            MenuLine(if (settings.keepScreenOn) "允许休眠" else "保持屏幕", "") {
                session.edit { it.copy(keepScreenOn = !it.keepScreenOn) }
            }
            MenuLine(if (settings.lockRotation) "允许旋转" else "禁止旋转", "") {
                session.edit { it.copy(lockRotation = !it.lockRotation) }
            }
            MenuLine("字体", settings.fontName()) {
                session.edit { it.copy(fontIndex = (it.fontIndex + 1) % 4) }
            }
            MenuLine("文件", "") {
                session.setScreen(Screen.FILES)
                actions.onClose()
            }
            MenuLine("噪声校准", "粉噪 / 白噪", actions.onNoise)
            MenuLine("存储当前测量", "", actions.onStore)
            MenuLine("关于与限制", "") { actions.onAbout() }
            Text(
                "点按频谱隐藏或显示按钮。光标打开时单指拖动光标，双指缩放先关掉光标。暂停后分析停住，示波器继续。暂停再运行会清空峰值存储并重开 Leq。",
                color = Dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}

internal class MenuActions(
    val onClose: () -> Unit,
    val onResolution: () -> Unit,
    val onMics: () -> Unit,
    val onFreqScale: () -> Unit,
    val onDbScale: () -> Unit,
    val onOffset: () -> Unit,
    val onImportCal: () -> Unit,
    val onPickCal: () -> Unit,
    val onSaveTarget: () -> Unit,
    val onPickTarget: () -> Unit,
    val onLeq: () -> Unit,
    val onChart: () -> Unit,
    val onNoise: () -> Unit,
    val onStore: () -> Unit,
    val onAbout: () -> Unit
)
