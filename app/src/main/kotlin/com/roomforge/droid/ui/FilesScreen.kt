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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roomforge.droid.Screen
import com.roomforge.droid.audio.Session
import java.io.File

@Composable
internal fun FilesScreen(
    session: Session,
    onShare: (File) -> Unit,
    onExport: (File) -> Unit,
    onImport: () -> Unit
) {
    var tick by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    val files = remember(tick, session.settings.value.overlayEpoch) { session.listFiles() }
    Column(Modifier.fillMaxSize()) {
        ScreenBack("文件", onBack = { session.setScreen(Screen.ANALYZER) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text(session.directory().absolutePath, color = Dim, fontSize = 11.sp)
            Text(
                "目录名沿用 AudioTool，方便和 Rex Beckett 的 REW 转换器放在一起。.at 列是频率、幅度、校正 dB、峰值、谷值，倍频程块附在后面。校正 dB = 20log10(幅度)+20+4.2+dBcalibration。",
                color = Dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyButton("导入") {
                    onImport()
                }
                KeyButton("清除叠层") {
                    session.clearOverlays()
                    tick++
                }
            }
            val a = session.settings.value.loadedNameA
            val b = session.settings.value.loadedNameB
            Text("叠层 A ${a ?: "空"}    B ${b ?: "空"}", color = Ink, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            if (files.isEmpty()) Text("还没有文件。", color = Ink)
            files.forEach { file ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(file.name, color = Ink, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KeyButton("打开") {
                            openFile(session, file)
                            tick++
                        }
                        KeyButton("分享") { onShare(file) }
                        KeyButton("导出") { onExport(file) }
                        KeyButton("删除") { pendingDelete = file }
                    }
                }
            }
        }
    }
    pendingDelete?.let { file ->
        ChoiceDialog(
            title = "删除 ${file.name}？",
            options = listOf("删除" to {
                session.deleteFile(file)
                tick++
            }),
            onDismiss = { pendingDelete = null }
        )
    }
}

private fun openFile(session: Session, file: File) {
    when (file.extension.lowercase()) {
        "at" -> {
            session.loadNextSpectrum(file)
            session.setScreen(Screen.ANALYZER)
        }
        "swp" -> {
            session.loadSwp(file)
            session.setScreen(Screen.ANALYZER)
        }
        "cht" -> {
            session.loadChart(file)
            session.setScreen(Screen.ANALYZER)
        }
        "leq" -> {
            session.loadLeq(file)
            session.setScreen(Screen.ANALYZER)
        }
        "rt60" -> session.loadRt60(file)
        "wav" -> {
            session.selectWav(file)
            session.edit { it.copy(showTransport = true, screen = Screen.ANALYZER) }
        }
        "targ" -> session.loadTarget(file)
        "cal", "txt", "frd" -> session.loadCalFile(file)
        else -> session.status("不认识的扩展名 ${file.extension}")
    }
}
