/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomforge.core.RtaMode
import com.roomforge.droid.UpdateState
import com.roomforge.droid.ui.AnalyzerScreen
import com.roomforge.droid.ui.Bg
import com.roomforge.droid.ui.CalScreen
import com.roomforge.droid.ui.ChoiceDialog
import com.roomforge.droid.ui.Dim
import com.roomforge.droid.ui.FilesScreen
import com.roomforge.droid.ui.GeneratorScreen
import com.roomforge.droid.ui.Ink
import com.roomforge.droid.ui.KeyButton
import com.roomforge.droid.ui.MenuActions
import com.roomforge.droid.ui.MenuSheet
import com.roomforge.droid.ui.NameDialog
import com.roomforge.droid.ui.NoticeDialog
import com.roomforge.droid.ui.NumberDialog
import com.roomforge.droid.ui.Panel
import com.roomforge.droid.ui.RoomForgeTheme
import com.roomforge.droid.ui.Rt60Screen
import com.roomforge.droid.ui.TargetScreen
import com.roomforge.droid.ui.UpdateDialog
import androidx.compose.runtime.collectAsState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoomViewModel(app: Application) : AndroidViewModel(app) {
    val session = com.roomforge.droid.audio.Session(app.applicationContext)

    override fun onCleared() {
        session.shutdown()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: RoomViewModel = viewModel()
            RoomForgeTheme {
                RoomForgeApp(model.session)
            }
        }
    }
}

private enum class Dialog {
    FREQ, DB, OFFSET, LEQ, STORE, TARGET_NAME, NOISE, ABOUT, MICS, FFT, CHART,
    TARGET_PICK, CAL_PICK
}

@Composable
private fun RoomForgeApp(session: com.roomforge.droid.audio.Session) {
    val activity = LocalContext.current as ComponentActivity
    val settings by session.settings.collectAsState()
    val frame by session.frames.collectAsState()
    val live by session.live.collectAsState()
    val rt60 by session.rt60Ui.collectAsState()
    val recordReady by session.recordReady.collectAsState()
    val update by session.update.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var askedMic by remember { mutableStateOf(false) }
    var calImport by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.start(granted)
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        dialog = Dialog.MICS
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(activity, uri)
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        if (calImport) session.importCal(name, bytes.toString(Charsets.UTF_8))
        else session.importExternal(name, bytes)
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exportFile
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        activity.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        session.status("已导出 ${file.name}")
    }

    val owner = LocalLifecycleOwner.current
    fun ensureAudio() {
        val ok = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) session.start(true)
        else if (!askedMic) {
            askedMic = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else session.start(false)
    }
    LaunchedEffect(Unit) { ensureAudio(); session.autoCheckUpdate() }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ensureAudio()
                Lifecycle.Event.ON_STOP -> session.standby()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(settings.lockRotation) {
        activity.requestedOrientation = if (settings.lockRotation) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = menu || dialog != null || settings.screen != Screen.ANALYZER) {
        when {
            dialog != null -> dialog = null
            menu -> menu = false
            else -> session.setScreen(Screen.ANALYZER)
        }
    }

    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * settings.fontScale())
    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides scaled) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            when (settings.screen) {
                Screen.ANALYZER -> AnalyzerScreen(session, settings, frame, live, onMenu = { menu = true }, onStore = { dialog = Dialog.STORE })
                Screen.GENERATOR -> GeneratorScreen(session, settings, live)
                Screen.RT60 -> Rt60Screen(session, settings, rt60) { dialog = Dialog.STORE }
                Screen.CAL -> CalScreen(session, settings) {
                    calImport = true
                    openDoc.launch(arrayOf("*/*"))
                }
                Screen.TARGET -> TargetScreen(
                    session,
                    settings,
                    onSave = { dialog = Dialog.TARGET_NAME },
                    onLoad = { dialog = Dialog.TARGET_PICK }
                )
                Screen.FILES -> FilesScreen(
                    session,
                    onShare = { share(activity, session, it) },
                    onExport = {
                        exportFile = it
                        createDoc.launch(it.name)
                    },
                    onImport = {
                        calImport = false
                        openDoc.launch(arrayOf("*/*"))
                    }
                )
            }
            if (menu) {
                MenuSheet(
                    session,
                    settings,
                    MenuActions(
                        onClose = { menu = false },
                        onResolution = { dialog = Dialog.FFT },
                        onMics = {
                            if (Build.VERSION.SDK_INT >= 31 &&
                                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                            ) {
                                btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else dialog = Dialog.MICS
                        },
                        onFreqScale = { dialog = Dialog.FREQ },
                        onDbScale = { dialog = Dialog.DB },
                        onOffset = { dialog = Dialog.OFFSET },
                        onImportCal = {
                            calImport = true
                            openDoc.launch(arrayOf("*/*"))
                        },
                        onPickCal = { dialog = Dialog.CAL_PICK },
                        onSaveTarget = { dialog = Dialog.TARGET_NAME },
                        onPickTarget = { dialog = Dialog.TARGET_PICK },
                        onLeq = { dialog = Dialog.LEQ },
                        onChart = { dialog = Dialog.CHART },
                        onNoise = { dialog = Dialog.NOISE },
                        onStore = { dialog = Dialog.STORE },
                        onAbout = { dialog = Dialog.ABOUT }
                    )
                )
            }
        }
    }

    if (recordReady) {
        NameDialog(
            title = "保存录音",
            hint = "最长约 30 秒，采样率与分析器相同。",
            initial = "rec.wav",
            confirm = "保存",
            onSave = { session.savePending(it) },
            onDiscard = { session.discardPending() }
        )
    }

    UpdateDialog(update, session)

    when (dialog) {
        Dialog.FREQ -> NumberDialog(
            title = "频率刻度",
            hint = "Hz。捏合与拖动也会改这个窗口。",
            labels = listOf("最低", "最高"),
            initial = listOf(settings.freqMin.toString(), settings.freqMax.toString()),
            onDismiss = { dialog = null },
            onConfirm = { n ->
                session.edit { it.copy(freqMin = n[0].coerceIn(10.0, 20000.0), freqMax = n[1].coerceIn(20.0, 24000.0)) }
                dialog = null
            }
        )
        Dialog.DB -> NumberDialog(
            title = "dB 刻度",
            hint = "最小、最大、网格步进。",
            labels = listOf("最小", "最大", "步进"),
            initial = listOf(settings.dbMin.toString(), settings.dbMax.toString(), settings.dbStep.toString()),
            onDismiss = { dialog = null },
            onConfirm = { n ->
                val step = n[2].coerceIn(1.0, 40.0)
                val min = n[0]
                val max = n[1].coerceAtLeast(min + step)
                session.edit { it.copy(dbMin = min, dbMax = max, dbStep = step) }
                dialog = null
            }
        )
        Dialog.OFFSET -> NumberDialog(
            title = "校准偏移",
            hint = "加到当前方式的全局 O=。宽带 SPL 使用它。1/3 圆盘是另外的修正。",
            labels = listOf("dB"),
            initial = listOf(
                (if (settings.calMode == com.roomforge.core.CalMode.SINGLE) settings.singleOffset else settings.thirdOffset).toString()
            ),
            onDismiss = { dialog = null },
            onConfirm = { n ->
                val v = n[0].coerceIn(-80.0, 140.0)
                session.edit { s ->
                    if (s.calMode == com.roomforge.core.CalMode.SINGLE) s.copy(singleOffset = v) else s.copy(thirdOffset = v)
                }
                dialog = null
            }
        )
        Dialog.LEQ -> NumberDialog(
            title = "Leq 时长",
            hint = "秒。常用 10、60、300、600。到时记入历史并重新开始，最多保留 5000 条。",
            labels = listOf("秒"),
            initial = listOf(settings.leqDuration.toString()),
            onDismiss = { dialog = null },
            onConfirm = { n ->
                session.edit { it.copy(leqDuration = n[0].coerceIn(1.0, 86400.0)) }
                dialog = null
            }
        )
        Dialog.STORE -> NameDialog(
            title = "存储",
            hint = "扩展名由当前画面决定：RT60→.rt60，Leq→.leq，记录→.cht，峰值→.swp，其余→.at。",
            initial = suggest(settings),
            confirm = "写入",
            onSave = {
                session.store(it)
                dialog = null
            },
            onDiscard = { dialog = null }
        )
        Dialog.TARGET_NAME -> NameDialog(
            title = "保存目标",
            hint = "写成 .targ，31 个 1/3 倍频程。",
            initial = "target.targ",
            confirm = "保存",
            onSave = {
                session.saveTarget(it)
                dialog = null
            },
            onDiscard = { dialog = null }
        )
        Dialog.FFT -> ChoiceDialog(
            title = "FFT",
            options = listOf(4096, 8192, 16384).map { n ->
                n.toString() to { session.edit { it.copy(fftSize = n) } }
            },
            onDismiss = { dialog = null }
        )
        Dialog.CHART -> ChoiceDialog(
            title = "记录图范围",
            options = listOf(
                "30 秒" to { session.edit { it.copy(chartSpan = 30.0, mode = RtaMode.CHART) } },
                "2 分钟" to { session.edit { it.copy(chartSpan = 120.0, mode = RtaMode.CHART) } },
                "10 分钟" to { session.edit { it.copy(chartSpan = 600.0, mode = RtaMode.CHART) } },
                "全部（最多约 10000 点）" to { session.edit { it.copy(chartSpan = 0.0, mode = RtaMode.CHART) } }
            ),
            onDismiss = { dialog = null }
        )
        Dialog.MICS -> {
            val options = ArrayList<Pair<String, () -> Unit>>()
            val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                options.add("请求麦克风权限" to { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            }
            session.mics().forEach { mic ->
                options.add(mic.label to { session.selectMic(mic) })
            }
            ChoiceDialog("麦克风", options) { dialog = null }
        }
        Dialog.CAL_PICK -> ChoiceDialog(
            title = "校准文件",
            options = session.listFiles().filter {
                val e = it.extension.lowercase()
                e == "cal" || e == "txt" || e == "frd"
            }.map { file ->
                file.name to { session.loadCalFile(file) }
            } + ("从其他位置" to {
                calImport = true
                openDoc.launch(arrayOf("*/*"))
            }),
            onDismiss = { dialog = null }
        )
        Dialog.TARGET_PICK -> ChoiceDialog(
            title = "目标曲线",
            options = session.listFiles(".targ").map { file ->
                file.name to { session.loadTarget(file) }
            } + ("从其他位置" to {
                calImport = false
                openDoc.launch(arrayOf("*/*"))
            }),
            onDismiss = { dialog = null }
        )
        Dialog.NOISE -> NoiseDialog(session) { dialog = null }
        Dialog.ABOUT -> NoticeDialog("关于 RoomForge", ABOUT, onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun NoiseDialog(session: com.roomforge.droid.audio.Session, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("噪声校准", color = Ink) },
        text = {
            Column {
                Text(
                    "用粉噪看 1/3 RTA：通路和 1/3 修正正确时，曲线应接近平直。白噪在倍频程上大约每倍频程上升 3 dB，在线性 FFT 上才接近平直。然后用声级计把 O= 对准一个已知声级。灵敏度因子不会代替声级计。",
                    color = Ink,
                    fontSize = 13.sp
                )
                KeyButton("粉噪 + 1/3") {
                    session.startNoiseDemo(true)
                    onDismiss()
                }
                KeyButton("白噪 + 1/3") {
                    session.startNoiseDemo(false)
                    onDismiss()
                }
                Text("白噪对照也可以切到 FFT。发生器页面能关掉输出。", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                session.edit { it.copy(generatorOn = false) }
                onDismiss()
            }) { Text("关闭发生器") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun displayName(activity: ComponentActivity, uri: Uri): String {
    activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0) ?: "import"
    }
    return uri.lastPathSegment ?: "import"
}

private fun share(activity: ComponentActivity, session: com.roomforge.droid.audio.Session, file: File) {
    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
    val type = if (file.extension.equals("wav", true)) "audio/wav" else "text/plain"
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { activity.startActivity(Intent.createChooser(send, file.name)) }
        .onFailure { session.status("没有可分享的应用") }
}

private fun suggest(settings: UiSettings): String {
    val ext = when {
        settings.screen == Screen.RT60 -> "rt60"
        settings.mode == RtaMode.LEQ -> "leq"
        settings.mode == RtaMode.CHART -> "cht"
        settings.mode == RtaMode.PEAK_STORE -> "swp"
        else -> "at"
    }
    val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
    return "$stamp.$ext"
}

private const val ABOUT = """RoomForge 是房间声学工具，文件布局兼容 AudioTool 的公开列格式。它不是 AudioTool，也不宣称符合 IEC 61672 Class 1。

手机内置麦克风出厂没有可追溯的声压校准。在你用声级计设定 O= 之前，SPL 是 dBFS 加上偏移。A/C 按 IEC 61672 模拟极点双线性变换，1 kHz 归一。X 曲线是 2048 点线性相位 FIR（约 3 dB/oct，2 kHz 低通与 60 Hz 高通串联），不是认证影院曲线。

Sone 用 ISO 226:2003 等响度，再套手册里的 HVI 组合：0.7×(1 kHz) + 0.3×(八个倍频程之和)。这是估计。Flat 计权才适合拿来算。

系统做不到的事：
· 没有公开 API 做“最近任务”按钮长按菜单。
· 前置麦走 CAMCORDER，系统可能忽略 setPreferredDevice。
· 蓝牙 SCO 是窄带通话通路。
· USB 只能用设备公布的采样率；列表为空时依次尝试 44100、48000…
· 已关闭 AEC/NS/AGC（如果效果器存在），厂商仍可能处理。UNPROCESSED 失败时退到 VOICE_RECOGNITION，再退到 MIC。
· 禁止旋转使用 Activity 方向锁，自由窗口或桌面模式可能不生效。
· 分享走 ACTION_SEND，导出走系统文件选择器，没有网盘 SDK。
· WAV 最长约 30 秒。Leq 历史最多 5000 条。记录图最多约 10000 点。
· 启动时检查一次 GitHub releases 有无新版（菜单里可关掉自动检查），下载和安装只在你确认后发生。

粉噪在 1/3 RTA 上应接近平直。白噪在倍频程上约 +3 dB/oct，在原始 FFT 上接近平直。"""
