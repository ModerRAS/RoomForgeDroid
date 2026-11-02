/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.roomforge.core.AnalysisPipeline
import com.roomforge.core.CalibrationFiles
import com.roomforge.core.ChartSample
import com.roomforge.core.Curve
import com.roomforge.core.DisplayFrame
import com.roomforge.core.IsoBands
import com.roomforge.core.LeqRecord
import com.roomforge.core.MeasurementFiles
import com.roomforge.core.Polarity
import com.roomforge.core.PolarityPulse
import com.roomforge.core.RtaMode
import com.roomforge.core.Rt60Phase
import com.roomforge.core.Rt60Recorder
import com.roomforge.core.Rt60Report
import com.roomforge.core.Rt60Row
import com.roomforge.core.SignalGenerator
import com.roomforge.core.SwpData
import com.roomforge.core.TimeConstant
import com.roomforge.core.Waveform
import com.roomforge.core.Weighting
import com.roomforge.droid.LiveReadout
import com.roomforge.droid.MicChoice
import com.roomforge.droid.MicKind
import com.roomforge.droid.Rt60Ui
import com.roomforge.droid.Screen
import com.roomforge.droid.UiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.abs

class SpecBuffer(val width: Int = 480, val height: Int = 200) {
    val rows: Array<DoubleArray> = Array(height) { DoubleArray(width) }

    @Volatile var published: Int = -1
    private var next = 0

    fun push(db: DoubleArray) {
        val row = rows[next]
        val n = minOf(width, db.size)
        for (i in 0 until n) row[i] = db[i]
        for (i in n until width) row[i] = -240.0
        published = next
        next = (next + 1) % height
    }
}

/**
 * Owns the microphone, the generator, RT60 and the files under
 * Android/data/&lt;package&gt;/files/AudioTool.
 */
class Session(private val context: Context) {
    val frames = MutableStateFlow<DisplayFrame?>(null)
    val settings = MutableStateFlow(UiSettings())
    val rt60Ui = MutableStateFlow(Rt60Ui())
    val spectrogram = SpecBuffer()
    val waterfall = SpecBuffer(height = 28)
    val recordReady = MutableStateFlow(false)
    val live = MutableStateFlow(LiveReadout())

    @Volatile var pendingPcm: Pair<Int, ShortArray>? = null

    var loadedA: Curve? = null
    var loadedB: Curve? = null
    var loadedSwp: SwpData? = null
    var loadedChart: List<ChartSample>? = null
    var loadedRt60: List<Rt60Row>? = null
    var loadedLeq: List<LeqRecord>? = null

    private val prefs = context.getSharedPreferences("roomforge", Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pipeline = AnalysisPipeline(44100.0, 8192)
    private var generator = SignalGenerator(44100.0)
    private var rt60 = Rt60Recorder(44100.0)

    @Volatile private var epoch = 0
    @Volatile private var micAllowed = false
    private var recorder: AudioRecord? = null
    private var playTrack: AudioTrack? = null
    private var wavTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private var wavThread: Thread? = null

    private val recordLock = Any()
    private val settingsLock = Any()
    private var recordBuf = ShortArray(0)
    private var recordN = 0
    private var wavSamples: ShortArray? = null
    private var wavRate = 0

    @Volatile private var playPos = 0
    private var pol = DoubleArray(0)
    private var polWrite = 0
    private var polCount = 0
    private var polTick = 0
    private var userToggledRun = false
    private var calBackup: UiSettings? = null
    private var targetBackup: List<Double>? = null
    private var lastPainted: Curve? = null
    private var fftFrames = 0
    private var closed = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            val usb = added.firstOrNull {
                it.isSource && (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
            }
            if (usb != null && settings.value.micDeviceId != usb.id) {
                val product = usb.productName?.toString().orEmpty().ifBlank { "device" }
                selectMic(MicChoice(MicKind.USB, usb.id, "USB-Audio $product"))
                status("已自动切换到 USB-Audio $product")
            }
        }
    }

    init {
        loadPrefs()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun start(permission: Boolean) {
        if (closed) return
        val changed = permission != micAllowed || captureThread == null || playThread == null
        micAllowed = permission
        if (!changed) {
            applyAll(settings.value)
            return
        }
        applyAll(settings.value)
        restartIo()
        if (!permission) status("没有麦克风权限。信号发生器和文件功能仍可使用。")
    }

    /** Drop the audio devices while the activity is stopped. Settings stay put. */
    fun standby() {
        epoch++
        releaseIo()
    }

    fun shutdown() {
        if (closed) return
        closed = true
        savePrefs(settings.value)
        epoch++
        releaseIo()
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    fun edit(save: Boolean = true, block: (UiSettings) -> UiSettings) {
        val prev = settings.value
        val next = mutate(block)
        if (engineChanged(prev, next)) applyAll(next, prev)
        if (save) savePrefs(next)
    }

    fun persist() {
        savePrefs(settings.value)
    }

    fun status(text: String) {
        mutate { it.copy(status = text) }
    }

    private fun mutate(block: (UiSettings) -> UiSettings): UiSettings = synchronized(settingsLock) {
        val next = block(settings.value)
        settings.value = next
        next
    }

    fun mics(): List<MicChoice> {
        val out = ArrayList<MicChoice>()
        out.add(MicChoice(MicKind.MAIN, -1, "Builtin Main"))
        out.add(MicChoice(MicKind.FRONT, -2, "Builtin Front"))
        val devices = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
            ?: return out
        for (d in devices) {
            if (!d.isSource) continue
            val product = d.productName?.toString().orEmpty().ifBlank { "device" }
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    out.add(MicChoice(MicKind.WIRED, d.id, "Wired Headset"))
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET ->
                    out.add(MicChoice(MicKind.USB, d.id, "USB-Audio $product"))
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    out.add(MicChoice(MicKind.BLUETOOTH, d.id, "Bluetooth $product"))
                else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    out.add(MicChoice(MicKind.BLUETOOTH, d.id, "Bluetooth $product"))
                }
            }
        }
        return out
    }

    fun selectMic(choice: MicChoice) {
        edit { it.copy(micKind = choice.kind, micDeviceId = choice.deviceId, micLabel = choice.label) }
        restartIo()
    }

    fun directory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "AudioTool").apply { mkdirs() }
    }

    fun listFiles(ext: String? = null): List<File> {
        val all = directory().listFiles()?.filter { it.isFile } ?: emptyList()
        val filtered = if (ext == null) all else all.filter { it.name.endsWith(ext, ignoreCase = true) }
        return filtered.sortedByDescending { it.lastModified() }
    }

    fun cycleMode() = edit { s ->
        val order = listOf(
            RtaMode.OCTAVE_1, RtaMode.OCTAVE_3, RtaMode.OCTAVE_6, RtaMode.OCTAVE_12,
            RtaMode.FULL, RtaMode.SPECTROGRAM, RtaMode.SPL, RtaMode.LEQ, RtaMode.CHART, RtaMode.PEAK_STORE
        )
        val idx = order.indexOf(if (s.mode == RtaMode.WATERFALL) RtaMode.SPECTROGRAM else s.mode)
        val next = order[(idx + 1).mod(order.size)]
        s.copy(mode = if (next == RtaMode.SPECTROGRAM && s.waterfall) RtaMode.WATERFALL else next)
    }

    fun cycleSpeed() = edit { s ->
        val order = listOf(TimeConstant.MEDIUM, TimeConstant.FAST, TimeConstant.SLOW, TimeConstant.IMPULSE)
        val next = order[(order.indexOf(s.speed) + 1).mod(order.size)]
        s.copy(speed = next)
    }

    fun cycleWeight() = edit { s ->
        val order = listOf(Weighting.FLAT, Weighting.A, Weighting.C, Weighting.X)
        s.copy(weighting = order[(order.indexOf(s.weighting) + 1).mod(order.size)])
    }

    fun toggleRunning() {
        val next = !settings.value.running
        if (next && userToggledRun) {
            pipeline.resetPeakStore()
            pipeline.resetLeq()
        }
        userToggledRun = true
        edit { it.copy(running = next) }
    }

    fun togglePeaks() = edit { it.copy(peaks = !it.peaks) }
    fun toggleValleys() = edit { it.copy(valleys = !it.valleys) }
    fun toggleAveraging() = edit { it.copy(averaging = !it.averaging) }

    fun setScreen(screen: Screen) {
        edit { it.copy(screen = screen) }
        if (screen == Screen.RT60) armRt60()
    }

    fun openCal() {
        if (settings.value.screen != Screen.CAL) beginCalEdit()
        edit { it.copy(screen = Screen.CAL) }
    }

    fun finishCal() {
        calBackup = null
        edit { it.copy(screen = Screen.ANALYZER) }
        status("校准已保留。O= 是加在 1/3 修正之上的全局偏移。")
    }

    fun openTarget() {
        if (settings.value.screen != Screen.TARGET) beginTargetEdit()
        edit { it.copy(screen = Screen.TARGET, showTarget = true) }
    }

    fun finishTarget() {
        targetBackup = null
        edit { it.copy(screen = Screen.ANALYZER, showTarget = true) }
    }

    fun toggleWaterfall() = edit { s ->
        val on = !s.waterfall
        val mode = when {
            on && (s.mode == RtaMode.SPECTROGRAM || s.mode == RtaMode.WATERFALL) -> RtaMode.WATERFALL
            !on && s.mode == RtaMode.WATERFALL -> RtaMode.SPECTROGRAM
            else -> s.mode
        }
        s.copy(waterfall = on, mode = mode)
    }

    fun clearLeq() {
        pipeline.resetLeq()
        pipeline.clearLeqHistory()
        status("Leq 已重新开始。")
    }

    fun beginCalEdit() { calBackup = settings.value }
    fun beginTargetEdit() { targetBackup = settings.value.target }

    fun revertCal() {
        val backup = calBackup ?: return
        calBackup = null
        edit {
            it.copy(
                calMode = backup.calMode,
                singleOffset = backup.singleOffset,
                thirdOffset = backup.thirdOffset,
                thirdBands = backup.thirdBands,
                sensDb = backup.sensDb,
                screen = Screen.ANALYZER
            )
        }
    }

    fun revertTarget() {
        val backup = targetBackup ?: return
        targetBackup = null
        edit { it.copy(target = backup, screen = Screen.ANALYZER) }
    }

    fun setBand(index: Int, value: Double, target: Boolean, save: Boolean = true) {
        edit(save) { s ->
            if (target) {
                val list = s.target.toMutableList()
                if (index in list.indices) list[index] = value
                s.copy(target = list)
            } else {
                val list = s.thirdBands.toMutableList()
                if (index in list.indices) list[index] = value
                s.copy(thirdBands = list, calMode = com.roomforge.core.CalMode.THIRD_OCTAVE)
            }
        }
    }

    fun zeroBands(target: Boolean) {
        edit { s ->
            if (target) s.copy(target = List(31) { 60.0 })
            else s.copy(thirdBands = List(31) { 0.0 })
        }
    }

    fun invertCal() = edit { s -> s.copy(thirdBands = s.thirdBands.map { -it }) }

    fun applySens() {
        val sens = settings.value.sensDb ?: return
        edit { s ->
            if (s.calMode == com.roomforge.core.CalMode.SINGLE) s.copy(singleOffset = s.singleOffset + sens)
            else s.copy(thirdOffset = s.thirdOffset + sens)
        }
        status("已把灵敏度因子 ${sens} dB 加到全局偏移。仍需用声级计核对。")
    }

    fun importCal(name: String, text: String) {
        val parsed = CalibrationFiles.parse(text)
        if (parsed.points.isEmpty()) {
            status("没有从 $name 读到频率/dB 行。")
            return
        }
        val corr = CalibrationFiles.toThirdOctave(parsed, treatAsResponse = true)
        val file = File(directory(), safeName(name.substringBeforeLast('.'), ".cal"))
        file.writeText(text)
        if (calBackup == null) beginCalEdit()
        edit { s ->
            s.copy(
                calMode = com.roomforge.core.CalMode.THIRD_OCTAVE,
                thirdBands = corr.toList(),
                sensDb = parsed.sensDb ?: s.sensDb,
                screen = Screen.CAL
            )
        }
        val sens = parsed.sensDb?.let { "，灵敏度因子 $it dB" } ?: ""
        status("已导入 ${parsed.points.size} 个频点并功率叠加到 1/3 倍频程$sens。1 kHz 被归一为 0。")
    }

    fun loadCalFile(file: File) {
        importCal(file.name, file.readText())
    }

    fun saveTarget(name: String) {
        val file = File(directory(), safeName(name, ".targ"))
        file.writeText(MeasurementFiles.writeTarget(settings.value.target.toDoubleArray()))
        status("已保存 ${file.name}")
    }

    fun loadTarget(file: File) {
        val levels = MeasurementFiles.readTarget(file.readText())
        edit { it.copy(target = levels.toList(), showTarget = true) }
        status("已加载目标曲线 ${file.name}")
    }

    fun startNoiseDemo(pink: Boolean) {
        edit {
            it.copy(
                screen = Screen.ANALYZER,
                mode = RtaMode.OCTAVE_3,
                weighting = Weighting.FLAT,
                running = true,
                generatorOn = true,
                waveform = if (pink) Waveform.PINK else Waveform.WHITE,
                volume = 0.25f,
                buttonsVisible = true
            )
        }
        status(
            if (pink) "粉噪已开。校准和通路正确时，1/3 RTA 应接近一条平线。用声级计设全局偏移。"
            else "白噪已开。倍频程大约每倍频程上升 3 dB；线性 FFT 才接近平直。"
        )
    }

    fun armRt60() {
        if (settings.value.generatorOn) {
            rt60.reset()
            rt60Ui.value = Rt60Ui(Rt60Phase.IDLE, null, rt60.liveDb)
            status("发生器开着：点「触发」会停掉发生器并测量衰减。")
        } else {
            rt60.armClap()
            rt60Ui.value = Rt60Ui(Rt60Phase.WAITING, null, rt60.liveDb)
        }
    }

    fun triggerRt60() {
        edit { it.copy(generatorOn = false) }
        rt60.trigger()
        rt60Ui.value = Rt60Ui(Rt60Phase.RECORDING, null, rt60.liveDb)
    }

    fun store(rawName: String): File? {
        val s = settings.value
        val ext = when {
            s.screen == Screen.RT60 -> ".rt60"
            s.mode == RtaMode.LEQ -> ".leq"
            s.mode == RtaMode.CHART -> ".cht"
            s.mode == RtaMode.PEAK_STORE -> ".swp"
            else -> ".at"
        }
        val text = when (ext) {
            ".rt60" -> {
                val report = rt60Ui.value.report ?: run { status("还没有 RT60 结果"); return null }
                MeasurementFiles.writeRt60(report)
            }
            ".leq" -> {
                val centers = IsoBands.octave1.map { it.centerHz }.toDoubleArray()
                val history = pipeline.allLeq().ifEmpty {
                    val view = frames.value?.leq
                    if (view == null || view.elapsedSec <= 0.0) emptyList()
                    else listOf(
                        LeqRecord(System.currentTimeMillis(), System.currentTimeMillis(), view.elapsedSec, view.broadbandDb, view.octaveDb)
                    )
                }
                if (history.isEmpty()) { status("还没有 Leq 数据"); return null }
                MeasurementFiles.writeLeq(history, centers)
            }
            ".cht" -> MeasurementFiles.writeChart(pipeline.allChart())
            ".swp" -> {
                val curve = frames.value?.peakStore ?: run { status("还没有峰值存储"); return null }
                MeasurementFiles.writeSwp(curve.freqHz, curve.db)
            }
            else -> {
                val dump = pipeline.dump(s.mode)
                if (dump.binFreqHz.isEmpty()) { status("还没有频谱可存"); return null }
                MeasurementFiles.writeSpectrum(dump)
            }
        }
        val file = File(directory(), safeName(rawName, ext))
        file.writeText(text)
        status("已写入 ${file.name}")
        return file
    }

    fun loadNextSpectrum(file: File) {
        val curve = MeasurementFiles.readSpectrum(file.readText())
        if (curve.size == 0) { status("文件里没有频谱"); return }
        if (loadedA == null) {
            loadedA = curve
            edit { it.copy(loadedNameA = file.name, overlayEpoch = it.overlayEpoch + 1) }
        } else if (loadedB == null) {
            loadedB = curve
            edit { it.copy(loadedNameB = file.name, overlayEpoch = it.overlayEpoch + 1) }
        } else {
            loadedA = loadedB
            loadedB = curve
            edit { it.copy(loadedNameA = it.loadedNameB, loadedNameB = file.name, overlayEpoch = it.overlayEpoch + 1) }
        }
    }

    fun clearSlot(slot: Int) {
        if (slot == 0) loadedA = null else loadedB = null
        edit {
            it.copy(
                loadedNameA = if (slot == 0) null else it.loadedNameA,
                loadedNameB = if (slot == 1) null else it.loadedNameB,
                overlayEpoch = it.overlayEpoch + 1
            )
        }
    }

    fun loadSwp(file: File) {
        loadedSwp = MeasurementFiles.readSwp(file.readText())
        edit { it.copy(loadedSwpName = file.name, overlayEpoch = it.overlayEpoch + 1, mode = RtaMode.PEAK_STORE) }
    }

    fun loadChart(file: File) {
        loadedChart = MeasurementFiles.readChart(file.readText())
        edit { it.copy(overlayEpoch = it.overlayEpoch + 1, mode = RtaMode.CHART) }
        status("已叠加载 ${file.name}（灰色，按文件自身时间）")
    }

    fun loadRt60(file: File) {
        loadedRt60 = MeasurementFiles.readRt60(file.readText())
        edit { it.copy(overlayEpoch = it.overlayEpoch + 1, screen = Screen.RT60) }
    }

    fun loadLeq(file: File) {
        loadedLeq = MeasurementFiles.readLeq(file.readText())
        edit { it.copy(overlayEpoch = it.overlayEpoch + 1, mode = RtaMode.LEQ) }
        status("已读入 ${loadedLeq?.size ?: 0} 条 Leq")
    }

    fun deleteFile(file: File) {
        file.delete()
        status("已删除 ${file.name}")
    }

    fun toggleRecord() {
        val s = settings.value
        if (s.recording) {
            val pcm = stopRecordLocked()
            if (pcm == null || pcm.second.isEmpty()) {
                edit { it.copy(recording = false, recordSeconds = 0.0) }
                status("没有录到样本")
            } else {
                pendingPcm = pcm
                edit { it.copy(recording = false) }
                recordReady.value = true
            }
        } else {
            synchronized(recordLock) {
                val rate = settings.value.sampleRate
                recordBuf = ShortArray(rate * 30)
                recordN = 0
            }
            edit { it.copy(recording = true, recordSeconds = 0.0) }
        }
    }

    fun savePending(name: String) {
        val pcm = pendingPcm ?: return
        val file = File(directory(), safeName(name, ".wav"))
        file.writeBytes(MeasurementFiles.writeWav(pcm.first, pcm.second))
        pendingPcm = null
        recordReady.value = false
        edit { it.copy(wavName = file.name) }
        status("已保存 ${file.name}")
    }

    fun discardPending() {
        pendingPcm = null
        recordReady.value = false
    }

    fun selectWav(file: File) {
        val pcm = MeasurementFiles.readWav(file.readBytes())
        if (pcm.sampleRate != settings.value.sampleRate) {
            status("WAV 采样率 ${pcm.sampleRate} 与分析器 ${settings.value.sampleRate} 不一致，不能播放。")
            return
        }
        wavSamples = pcm.samples
        wavRate = pcm.sampleRate
        playPos = 0
        edit { it.copy(wavName = file.name, playing = false) }
    }

    fun togglePlay() {
        if (wavSamples == null) { status("先选择一个 WAV"); return }
        edit { it.copy(playing = !it.playing) }
    }

    fun rewind() { playPos = 0 }

    fun clearOverlays() {
        loadedA = null
        loadedB = null
        loadedSwp = null
        loadedChart = null
        loadedRt60 = null
        loadedLeq = null
        edit {
            it.copy(
                loadedNameA = null,
                loadedNameB = null,
                loadedSwpName = null,
                overlayEpoch = it.overlayEpoch + 1
            )
        }
    }

    fun importExternal(displayName: String, bytes: ByteArray) {
        val lower = displayName.lowercase()
        val ext = when {
            lower.endsWith(".wav") -> ".wav"
            lower.endsWith(".targ") -> ".targ"
            lower.endsWith(".rt60") -> ".rt60"
            lower.endsWith(".leq") -> ".leq"
            lower.endsWith(".cht") -> ".cht"
            lower.endsWith(".swp") -> ".swp"
            lower.endsWith(".at") -> ".at"
            lower.endsWith(".cal") || lower.endsWith(".frd") || lower.endsWith(".txt") -> ".cal"
            else -> {
                val head = bytes.decodeToString(0, minOf(80, bytes.size))
                when {
                    head.contains("dBcalibration") || head.contains("Octave Data") -> ".at"
                    head.contains("Target") -> ".targ"
                    else -> {
                        status("无法识别 $displayName")
                        return
                    }
                }
            }
        }
        if (ext == ".cal") {
            importCal(displayName, bytes.toString(Charsets.UTF_8))
            return
        }
        val file = File(directory(), safeName(displayName, ext))
        file.writeBytes(bytes)
        when (ext) {
            ".wav" -> selectWav(file)
            ".targ" -> loadTarget(file)
            ".rt60" -> loadRt60(file)
            ".leq" -> loadLeq(file)
            ".cht" -> loadChart(file)
            ".swp" -> loadSwp(file)
            ".at" -> loadNextSpectrum(file)
            else -> status("已复制 ${file.name}")
        }
    }

    fun latestOctave3(): DoubleArray? = frames.value?.oct3?.db?.copyOf()

    private fun stopRecordLocked(): Pair<Int, ShortArray>? = synchronized(recordLock) {
        val n = recordN
        if (n <= 0) return null
        val copy = recordBuf.copyOf(n)
        recordN = 0
        settings.value.sampleRate to copy
    }

    private fun applyAll(s: UiSettings, prev: UiSettings? = null) {
        if (pipeline.fftSize != s.fftSize) pipeline.setFftSize(s.fftSize)
        if (pipeline.weighting != s.weighting) pipeline.setWeighting(s.weighting)
        pipeline.setCalibrationLive(s.calibration())
        pipeline.applyRuntime(
            paused = !s.running,
            timeConstant = s.speed,
            averagingEnabled = s.averaging,
            peaksEnabled = s.peaks,
            valleysEnabled = s.valleys,
            capturePeakStore = s.mode == RtaMode.PEAK_STORE,
            leqDurationSec = s.leqDuration
        )
        generator.waveform = s.waveform
        generator.frequency = s.genHz
        generator.volume = s.volume.toDouble()
        generator.leftOn = s.leftOn
        generator.rightOn = s.rightOn
        generator.invertRight = s.invertRight
        generator.sweepStartHz = s.sweepStart
        generator.sweepEndHz = s.sweepEnd
        generator.sweepDurationSec = s.sweepDur
        generator.impulseIntervalSec = s.impulseSec
        val micChanged = prev == null || prev.micKind != s.micKind || prev.micDeviceId != s.micDeviceId
        if (micChanged) routeBluetooth(s)
    }

    private fun engineChanged(a: UiSettings, b: UiSettings): Boolean =
        a.fftSize != b.fftSize || a.weighting != b.weighting || a.running != b.running ||
            a.speed != b.speed || a.averaging != b.averaging || a.peaks != b.peaks ||
            a.valleys != b.valleys || a.mode != b.mode || a.leqDuration != b.leqDuration ||
            a.calMode != b.calMode || a.singleOffset != b.singleOffset ||
            a.thirdOffset != b.thirdOffset || a.thirdBands != b.thirdBands ||
            a.waveform != b.waveform || a.genHz != b.genHz || a.volume != b.volume ||
            a.leftOn != b.leftOn || a.rightOn != b.rightOn || a.invertRight != b.invertRight ||
            a.sweepStart != b.sweepStart || a.sweepEnd != b.sweepEnd || a.sweepDur != b.sweepDur ||
            a.impulseSec != b.impulseSec || a.micKind != b.micKind || a.micDeviceId != b.micDeviceId ||
            a.generatorOn != b.generatorOn

    @SuppressLint("MissingPermission")
    private fun routeBluetooth(s: UiSettings) {
        runCatching {
            if (s.micKind != MicKind.BLUETOOTH) {
                if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
                return
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == s.micDeviceId }
            if (Build.VERSION.SDK_INT >= 31) {
                if (device != null) audioManager.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
        }.onFailure {
            status("蓝牙路由失败：${it.javaClass.simpleName}。SCO 频带很窄，优先用有线或 USB。")
        }
    }

    private fun restartIo() {
        epoch++
        val token = epoch
        releaseIo()
        val s = settings.value
        val device = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == s.micDeviceId }
        }.getOrNull()
        val rate = chooseRate(if (s.micKind == MicKind.MAIN || s.micKind == MicKind.FRONT) null else device)
        retarget(rate)
        if (micAllowed) openCapture(token, s, device, rate)
        openTracks(token, rate)
    }

    private fun retarget(rate: Int) {
        if (pipeline.sampleRate.toInt() != rate) pipeline.setSampleRate(rate.toDouble())
        val old = generator
        generator = SignalGenerator(rate.toDouble()).also {
            it.waveform = old.waveform
            it.frequency = old.frequency
            it.volume = old.volume
            it.leftOn = old.leftOn
            it.rightOn = old.rightOn
            it.invertRight = old.invertRight
            it.sweepStartHz = old.sweepStartHz
            it.sweepEndHz = old.sweepEndHz
            it.sweepDurationSec = old.sweepDurationSec
            it.impulseIntervalSec = old.impulseIntervalSec
        }
        rt60 = Rt60Recorder(rate.toDouble())
        pol = DoubleArray(0)
        mutate { it.copy(sampleRate = rate) }
        applyAll(settings.value)
    }

    @SuppressLint("MissingPermission")
    private fun openCapture(token: Int, s: UiSettings, device: AudioDeviceInfo?, rate: Int) {
        val sources = when (s.micKind) {
            MicKind.FRONT -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
            else -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        }
        var opened: AudioRecord? = null
        for (source in sources) {
            opened = tryOpen(source, rate, if (s.micKind == MicKind.FRONT) null else device)
            if (opened != null) break
        }
        val rec = opened ?: run {
            status("打不开麦克风。发生器仍可发声。")
            return
        }
        recorder = rec
        disableFx(rec.audioSessionId)
        rec.startRecording()
        captureThread = Thread {
            val buf = ShortArray(2048)
            var uiTick = 0
            while (epoch == token) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    break
                }
                if (n <= 0) {
                    if (epoch != token) break
                    continue
                }
                maybeRecord(buf, n)
                val doubles = DoubleArray(n) { buf[it] / 32768.0 }
                val cfg = settings.value
                val tone = if (cfg.generatorOn && generator.isSweeping()) generator.currentHz else Double.NaN
                val frame = pipeline.push(doubles, tone) ?: continue
                if (cfg.screen == Screen.RT60 || rt60.phase == Rt60Phase.WAITING || rt60.phase == Rt60Phase.RECORDING) {
                    rt60.push(doubles)
                }
                val polarity = if (cfg.generatorOn && cfg.waveform == Waveform.POLARITY) {
                    detectPolarity(doubles, rate)
                } else {
                    null
                }
                if (frame.full.size > 0 && frame.full !== lastPainted) {
                    lastPainted = frame.full
                    fftFrames++
                    if (cfg.spectrogramFast || fftFrames % 4 == 0) spectrogram.push(frame.full.db)
                    if (fftFrames % 2 == 0) waterfall.push(frame.full.db)
                }
                frames.value = frame
                uiTick++
                if (uiTick % 4 == 0) {
                    live.value = LiveReadout(
                        frameMillis = frame.processingMillis,
                        polarity = polarity,
                        recordSeconds = synchronized(recordLock) { recordN.toDouble() / rate.coerceAtLeast(1) }
                    )
                    rt60Ui.value = Rt60Ui(rt60.phase, rt60.report, rt60.liveDb)
                }
            }
        }.also { it.name = "roomforge-capture"; it.start() }
    }

    private fun tryOpen(source: Int, rate: Int, device: AudioDeviceInfo?): AudioRecord? {
        return try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, rate) * 2)
                .build()
            // false means the route is unsupported. Keep the recorder: some USB
            // stacks return false even when the default input is already that device.
            if (device != null) rec.setPreferredDevice(device)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            rec
        } catch (_: Exception) {
            null
        }
    }

    private fun disableFx(sessionId: Int) {
        runCatching { AutomaticGainControl.create(sessionId)?.enabled = false }
        runCatching { NoiseSuppressor.create(sessionId)?.enabled = false }
        runCatching { AcousticEchoCanceler.create(sessionId)?.enabled = false }
    }

    private fun maybeRecord(buf: ShortArray, n: Int) {
        if (!settings.value.recording) return
        synchronized(recordLock) {
            if (recordBuf.isEmpty()) return
            val room = recordBuf.size - recordN
            val take = minOf(room, n)
            if (take > 0) {
                buf.copyInto(recordBuf, recordN, 0, take)
                recordN += take
            }
            if (recordN >= recordBuf.size) {
                val pcm = recordBuf.copyOf(recordN)
                recordN = 0
                pendingPcm = settings.value.sampleRate to pcm
                recordReady.value = true
                mutate { it.copy(recording = false) }
            }
        }
    }

    private fun detectPolarity(samples: DoubleArray, rate: Int): String? {
        val need = (rate * 0.7).toInt().coerceAtLeast(256)
        if (pol.size != need) {
            pol = DoubleArray(need)
            polWrite = 0
            polCount = 0
        }
        for (s in samples) {
            pol[polWrite] = s
            polWrite = (polWrite + 1) % pol.size
            if (polCount < pol.size) polCount++
        }
        polTick += samples.size
        if (polTick < rate / 4 || polCount < need / 2) return settings.value.polarity
        polTick = 0
        val snap = DoubleArray(polCount)
        val start = if (polCount < pol.size) 0 else polWrite
        for (i in snap.indices) snap[i] = pol[(start + i) % pol.size]
        return when (PolarityPulse.detect(snap, PolarityPulse.template(rate.toDouble()))) {
            Polarity.POSITIVE -> "Pol +++"
            Polarity.NEGATIVE -> "Pol ---"
            Polarity.UNKNOWN -> "Pol ?"
        }
    }

    private fun openTracks(token: Int, rate: Int) {
        playTrack = buildTrack(rate)
        wavTrack = buildTrack(rate)
        playThread = Thread {
            val track = playTrack ?: return@Thread
            val interleaved = ShortArray(2048)
            while (epoch == token) {
                val on = settings.value.generatorOn
                if (!on) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    try { Thread.sleep(40) } catch (_: InterruptedException) { break }
                    continue
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                val stereo = generator.renderStereo(1024)
                for (i in 0 until 1024) {
                    interleaved[2 * i] = (stereo.left[i] * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
                    interleaved[2 * i + 1] = (stereo.right[i] * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                try {
                    track.write(interleaved, 0, interleaved.size)
                } catch (_: Exception) {
                    break
                }
            }
        }.also { it.name = "roomforge-gen"; it.start() }
        wavThread = Thread {
            val track = wavTrack ?: return@Thread
            val interleaved = ShortArray(2048)
            while (epoch == token) {
                val playing = settings.value.playing
                val data = wavSamples
                if (!playing || data == null) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    try { Thread.sleep(40) } catch (_: InterruptedException) { break }
                    continue
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                var i = 0
                while (i < 1024 && playPos < data.size) {
                    val s = data[playPos++]
                    interleaved[2 * i] = s
                    interleaved[2 * i + 1] = s
                    i++
                }
                if (i == 0) {
                    playPos = 0
                    mutate { it.copy(playing = false) }
                    continue
                }
                try {
                    track.write(interleaved, 0, i * 2)
                } catch (_: Exception) {
                    break
                }
            }
        }.also { it.name = "roomforge-wav"; it.start() }
    }

    private fun buildTrack(rate: Int): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate / 2) * 4)
            .build()
    } catch (_: Exception) {
        status("音频输出打不开（采样率 $rate）。")
        null
    }

    private fun chooseRate(device: AudioDeviceInfo?): Int {
        val preferred = intArrayOf(44100, 48000, 32000, 22050, 16000)
        val advertised = device?.sampleRates?.filter { it > 0 }?.toSet().orEmpty()
        if (advertised.isNotEmpty()) {
            for (r in preferred) if (r in advertised) return r
            return advertised.minBy { abs(it - 44100) }
        }
        for (r in preferred) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return r
        }
        return 44100
    }

    private fun releaseIo() {
        val cap = captureThread
        val gen = playThread
        val wav = wavThread
        val rec = recorder
        val play = playTrack
        val wavOut = wavTrack
        captureThread = null
        playThread = null
        wavThread = null
        recorder = null
        playTrack = null
        wavTrack = null
        rec?.runCatching { stop() }
        play?.runCatching { pause() }
        wavOut?.runCatching { pause() }
        cap?.join(800)
        gen?.join(800)
        wav?.join(800)
        rec?.runCatching { release() }
        play?.runCatching { flush(); release() }
        wavOut?.runCatching { flush(); release() }
    }

    private fun loadPrefs() {
        fun csv(key: String, default: List<Double>): List<Double> {
            val raw = prefs.getString(key, null) ?: return default
            val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
            return if (parts.size == default.size) parts else default
        }
        val s = UiSettings(
            mode = runCatching { RtaMode.valueOf(prefs.getString("mode", "OCTAVE_3")!!) }.getOrDefault(RtaMode.OCTAVE_3),
            averaging = prefs.getBoolean("avg", false),
            peaks = prefs.getBoolean("peaks", true),
            valleys = prefs.getBoolean("valleys", false),
            speed = runCatching { TimeConstant.valueOf(prefs.getString("speed", "MEDIUM")!!) }.getOrDefault(TimeConstant.MEDIUM),
            weighting = runCatching { Weighting.valueOf(prefs.getString("weight", "FLAT")!!) }.getOrDefault(Weighting.FLAT),
            fftSize = prefs.getInt("fft", 8192),
            micKind = runCatching { MicKind.valueOf(prefs.getString("micKind", "MAIN")!!) }.getOrDefault(MicKind.MAIN),
            micDeviceId = prefs.getInt("micId", -1),
            micLabel = prefs.getString("micLabel", "Builtin Main") ?: "Builtin Main",
            hideInput = prefs.getBoolean("hideIn", false),
            showTransport = prefs.getBoolean("transport", false),
            showCursors = prefs.getBoolean("cursors", false),
            showNotes = prefs.getBoolean("notes", true),
            showSone = prefs.getBoolean("sone", false),
            showNc = prefs.getBoolean("nc", false),
            showNr = prefs.getBoolean("nr", false),
            showTarget = prefs.getBoolean("showTarget", true),
            freqMin = prefs.getFloat("fmin", 20f).toDouble(),
            freqMax = prefs.getFloat("fmax", 20000f).toDouble(),
            freqLinear = prefs.getBoolean("flin", false),
            dbMin = prefs.getFloat("dmin", 20f).toDouble(),
            dbMax = prefs.getFloat("dmax", 110f).toDouble(),
            dbStep = prefs.getFloat("dstep", 15f).toDouble(),
            spectrogramFast = prefs.getBoolean("specFast", true),
            waterfall = prefs.getBoolean("waterfall", false),
            leqDuration = prefs.getFloat("leq", 60f).toDouble(),
            keepScreenOn = prefs.getBoolean("screen", true),
            lockRotation = prefs.getBoolean("rotate", false),
            fontIndex = prefs.getInt("font", 1),
            calMode = runCatching { com.roomforge.core.CalMode.valueOf(prefs.getString("cal", "SINGLE")!!) }.getOrDefault(com.roomforge.core.CalMode.SINGLE),
            singleOffset = prefs.getFloat("off1", 0f).toDouble(),
            thirdOffset = prefs.getFloat("off3", 0f).toDouble(),
            thirdBands = csv("bands", List(31) { 0.0 }),
            target = csv("target", List(31) { 60.0 }),
            sensDb = prefs.getString("sens", null)?.toDoubleOrNull(),
            genHz = prefs.getFloat("hz", 1000f).toDouble(),
            volume = prefs.getFloat("vol", 0.2f),
            waveform = runCatching { Waveform.valueOf(prefs.getString("wave", "SINE")!!) }.getOrDefault(Waveform.SINE),
            sweepStart = prefs.getFloat("sw0", 100f).toDouble(),
            sweepEnd = prefs.getFloat("sw1", 2000f).toDouble(),
            sweepDur = prefs.getFloat("swd", 10f).toDouble(),
            impulseSec = prefs.getFloat("impulse", 1f).toDouble(),
            leftOn = prefs.getBoolean("left", true),
            rightOn = prefs.getBoolean("right", true),
            invertRight = prefs.getBoolean("invert", false),
            chartSpan = prefs.getFloat("chartSpan", 0f).toDouble(),
            cursorA = prefs.getFloat("curA", 200f).toDouble(),
            cursorB = prefs.getFloat("curB", 2000f).toDouble(),
            rt60Table = prefs.getBoolean("rt60table", true)
        )
        settings.value = s
        applyAll(s)
    }

    private fun savePrefs(s: UiSettings) {
        prefs.edit()
            .putString("mode", s.mode.name)
            .putBoolean("avg", s.averaging)
            .putBoolean("peaks", s.peaks)
            .putBoolean("valleys", s.valleys)
            .putString("speed", s.speed.name)
            .putString("weight", s.weighting.name)
            .putInt("fft", s.fftSize)
            .putString("micKind", s.micKind.name)
            .putInt("micId", s.micDeviceId)
            .putString("micLabel", s.micLabel)
            .putBoolean("hideIn", s.hideInput)
            .putBoolean("transport", s.showTransport)
            .putBoolean("cursors", s.showCursors)
            .putBoolean("notes", s.showNotes)
            .putBoolean("sone", s.showSone)
            .putBoolean("nc", s.showNc)
            .putBoolean("nr", s.showNr)
            .putBoolean("showTarget", s.showTarget)
            .putFloat("fmin", s.freqMin.toFloat())
            .putFloat("fmax", s.freqMax.toFloat())
            .putBoolean("flin", s.freqLinear)
            .putFloat("dmin", s.dbMin.toFloat())
            .putFloat("dmax", s.dbMax.toFloat())
            .putFloat("dstep", s.dbStep.toFloat())
            .putBoolean("specFast", s.spectrogramFast)
            .putBoolean("waterfall", s.waterfall)
            .putFloat("leq", s.leqDuration.toFloat())
            .putBoolean("screen", s.keepScreenOn)
            .putBoolean("rotate", s.lockRotation)
            .putInt("font", s.fontIndex)
            .putString("cal", s.calMode.name)
            .putFloat("off1", s.singleOffset.toFloat())
            .putFloat("off3", s.thirdOffset.toFloat())
            .putString("bands", s.thirdBands.joinToString(","))
            .putString("target", s.target.joinToString(","))
            .putString("sens", s.sensDb?.toString())
            .putFloat("hz", s.genHz.toFloat())
            .putFloat("vol", s.volume)
            .putString("wave", s.waveform.name)
            .putFloat("sw0", s.sweepStart.toFloat())
            .putFloat("sw1", s.sweepEnd.toFloat())
            .putFloat("swd", s.sweepDur.toFloat())
            .putFloat("impulse", s.impulseSec.toFloat())
            .putBoolean("left", s.leftOn)
            .putBoolean("right", s.rightOn)
            .putBoolean("invert", s.invertRight)
            .putFloat("chartSpan", s.chartSpan.toFloat())
            .putFloat("curA", s.cursorA.toFloat())
            .putFloat("curB", s.cursorB.toFloat())
            .putBoolean("rt60table", s.rt60Table)
            .apply()
    }

    companion object {
        fun safeName(raw: String, ext: String): String {
            val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48)
            val stem = cleaned.ifBlank { "measure" }.removeSuffix(ext)
            return stem + ext
        }
    }
}
