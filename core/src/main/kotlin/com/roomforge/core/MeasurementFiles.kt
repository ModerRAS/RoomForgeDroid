/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.pow

data class SwpData(val freqHz: DoubleArray, val peakDb: DoubleArray)
data class Rt60Row(val name: String, val earlySec: Double, val t15to20Sec: Double)
data class WavPcm(val sampleRate: Int, val samples: ShortArray)

/**
 * Text files follow the AudioTool column layout where that layout is documented:
 * .at rows are freq, magnitude, corrected dB, peak, valley, and an octave block
 * is appended in octave modes. Corrected dB is authoritative. Magnitude is written
 * so the manual's v6.0.3+ reconstruction holds with the header calibration:
 * dB = 20 log10(mag) + 20 + 4.2 + dBcalibration.
 * Rex Beckett's REW converter reads the appended 1/3-octave block; that block is
 * the calibrated band level, valley and peak.
 */
object MeasurementFiles {
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun now(): String = stamp.format(Instant.now())

    fun writeSpectrum(dump: SpectrumDump, whenText: String = now()): String {
        val cal = dump.globalCalibrationDb
        val sb = StringBuilder()
        sb.append("RoomForgeDroid spectrum AudioTool-compatible recorded at ").append(whenText).append('\n')
        sb.append("dBcalibration=").append(cal)
            .append(" dBvonHann=4.2 dBcorrection=20")
            .append(" sampleRate=").append(dump.sampleRate)
            .append(" fft=").append(dump.fftSize)
            .append(" weighting=").append(dump.weighting.name)
            .append('\n')
        sb.append("formula: corrected_dB = 20*log10(mag)+dBcorrection+dBvonHann+dBcalibration\n")
        val n = dump.binFreqHz.size
        for (i in 0 until n) {
            if (dump.binFreqHz[i] <= 0.0) continue
            val db = dump.binDb[i]
            val mag = magnitudeForFormula(db, cal)
            sb.append(dump.binFreqHz[i]).append(' ')
                .append(mag).append(' ')
                .append(db).append(' ')
                .append(dump.binPeakDb[i]).append(' ')
                .append(dump.binValleyDb[i]).append('\n')
        }
        val title = dump.bandTitle
        val freq = dump.bandFreqHz
        val db = dump.bandDb
        val valley = dump.bandValleyDb
        val peak = dump.bandPeakDb
        if (title != null && freq != null && db != null && valley != null && peak != null) {
            sb.append(title).append(": Columns are Frequency(Hz), dB, dB(valley), dB(peak)\n")
            for (i in freq.indices) {
                sb.append(freq[i]).append(' ')
                    .append(db[i]).append(' ')
                    .append(valley[i]).append(' ')
                    .append(peak[i]).append('\n')
            }
        }
        return sb.toString()
    }

    fun magnitudeForFormula(correctedDb: Double, calibrationDb: Double): Double {
        val logMag = (correctedDb - 20.0 - 4.2 - calibrationDb) / 20.0
        return 10.0.pow(logMag)
    }

    fun readSpectrum(text: String): Curve {
        val freq = ArrayList<Double>()
        val db = ArrayList<Double>()
        val of = ArrayList<Double>()
        val od = ArrayList<Double>()
        var octave = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.contains("Octave Data", ignoreCase = true)) {
                octave = true
                continue
            }
            val parts = line.split(Regex("\\s+"))
            val numbers = ArrayList<Double>()
            for (p in parts) {
                val v = p.toDoubleOrNull() ?: break
                numbers.add(v)
            }
            if (numbers.size < 2 || numbers.size != parts.size) continue
            if (!octave) {
                freq.add(numbers[0])
                db.add(if (numbers.size >= 3) numbers[2] else numbers[1])
            } else {
                of.add(numbers[0])
                od.add(numbers[1])
            }
        }
        val useOctave = freq.size < 32 && of.isNotEmpty()
        val f = (if (useOctave) of else freq).toDoubleArray()
        val d = (if (useOctave) od else db).toDoubleArray()
        return Curve(f, d, d.copyOf(), d.copyOf())
    }

    fun writeLeq(records: List<LeqRecord>, centers: DoubleArray, whenText: String = now()): String {
        val sb = StringBuilder()
        sb.append("RoomForgeDroid Leq File recorded at ").append(whenText).append('\n')
        sb.append("MilliSeconds now: ").append(System.currentTimeMillis())
            .append(" Columns: Time Start, Time Finish, Duration(s), LEQ")
        for (c in centers) sb.append(", LEQ ").append(c.toInt())
        sb.append(' ').append(records.size).append(" LEQ measurements in file\n")
        for (r in records) {
            sb.append(stamp.format(Instant.ofEpochMilli(r.startEpochMs))).append(' ')
            sb.append(stamp.format(Instant.ofEpochMilli(r.endEpochMs))).append(' ')
            sb.append(r.durationSec).append(' ')
            sb.append(r.broadbandDb)
            for (v in r.octaveDb) sb.append(' ').append(v)
            sb.append('\n')
        }
        return sb.toString()
    }

    fun readLeq(text: String): List<LeqRecord> {
        val out = ArrayList<LeqRecord>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 14) continue
            if (parts[0].toDoubleOrNull() != null) continue
            val start = parseStamp(parts[0]) ?: continue
            val end = parseStamp(parts[1]) ?: continue
            val dur = parts[2].toDoubleOrNull() ?: continue
            val broad = parts[3].toDoubleOrNull() ?: continue
            val oct = DoubleArray(parts.size - 4) { parts[it + 4].toDouble() }
            out.add(LeqRecord(start, end, dur, broad, oct))
        }
        return out
    }

    fun writeChart(samples: List<ChartSample>, whenText: String = now()): String {
        val sb = StringBuilder()
        sb.append("RoomForgeDroid Chart recorded at ").append(whenText).append('\n')
        sb.append("Columns: Time(s) SPL(dB) PeakFreq(Hz) SweepFreq(Hz)\n")
        for (s in samples) {
            sb.append(s.timeSec).append(' ')
                .append(s.splDb).append(' ')
                .append(s.peakHz).append(' ')
                .append(if (s.sweepHz.isNaN()) "nan" else s.sweepHz.toString())
                .append('\n')
        }
        return sb.toString()
    }

    fun readChart(text: String): List<ChartSample> {
        val out = ArrayList<ChartSample>()
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val t = parts[0].toDoubleOrNull() ?: continue
            val spl = parts[1].toDoubleOrNull() ?: continue
            val peak = parts[2].toDoubleOrNull() ?: continue
            val sweep = if (parts.size >= 4) parts[3].toDoubleOrNull() ?: Double.NaN else Double.NaN
            out.add(ChartSample(t, spl, peak, sweep))
        }
        return out
    }

    fun writeSwp(freq: DoubleArray, peak: DoubleArray, whenText: String = now()): String {
        val sb = StringBuilder()
        sb.append("RoomForgeDroid Peak Store 1/6 recorded at ").append(whenText).append('\n')
        sb.append("Columns: Frequency(Hz) Peak(dB)\n")
        for (i in freq.indices) sb.append(freq[i]).append(' ').append(peak[i]).append('\n')
        return sb.toString()
    }

    fun readSwp(text: String): SwpData {
        val f = ArrayList<Double>()
        val p = ArrayList<Double>()
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val freq = parts[0].toDoubleOrNull() ?: continue
            val db = parts[1].toDoubleOrNull() ?: continue
            f.add(freq)
            p.add(db)
        }
        return SwpData(f.toDoubleArray(), p.toDoubleArray())
    }

    fun writeRt60(report: Rt60Report, whenText: String = now()): String {
        val sb = StringBuilder()
        sb.append("RoomForgeDroid RT60 recorded at ").append(whenText).append('\n')
        sb.append("Columns: BandHz RTEarly_s T15_20_s\n")
        sb.append("Wideband ").append(report.wideband.earlySec).append(' ').append(report.wideband.t15to20Sec).append('\n')
        for ((hz, metrics) in report.bands) {
            sb.append(hz).append(' ').append(metrics.earlySec).append(' ').append(metrics.t15to20Sec).append('\n')
        }
        return sb.toString()
    }

    fun readRt60(text: String): List<Rt60Row> {
        val out = ArrayList<Rt60Row>()
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val early = parts[1].toDoubleOrNull() ?: continue
            val late = parts[2].toDoubleOrNull() ?: continue
            out.add(Rt60Row(parts[0], early, late))
        }
        return out
    }

    fun writeTarget(levels: DoubleArray, comment: String = "RoomForgeDroid Target Curve"): String {
        val sb = StringBuilder()
        sb.append(comment).append('\n')
        val bands = IsoBands.octave3
        for (i in bands.indices) {
            val level = if (i < levels.size) levels[i] else 60.0
            sb.append(trim(bands[i].centerHz)).append(' ').append(level).append('\n')
        }
        return sb.toString()
    }

    fun readTarget(text: String): DoubleArray {
        val levels = DoubleArray(IsoBands.octave3.size) { 60.0 }
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val freq = parts[0].toDoubleOrNull() ?: continue
            val db = parts[1].toDoubleOrNull() ?: continue
            val idx = IsoBands.octave3.indexOfFirst { kotlin.math.abs(it.centerHz - freq) / it.centerHz < 0.08 }
            if (idx >= 0) levels[idx] = db
        }
        return levels
    }

    fun writeWav(sampleRate: Int, samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)
        fun ascii(at: Int, s: String) {
            for (i in s.indices) out[at + i] = s[i].code.toByte()
        }
        fun le32(at: Int, v: Int) {
            out[at] = v.toByte()
            out[at + 1] = (v shr 8).toByte()
            out[at + 2] = (v shr 16).toByte()
            out[at + 3] = (v shr 24).toByte()
        }
        fun le16(at: Int, v: Int) {
            out[at] = v.toByte()
            out[at + 1] = (v shr 8).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, 1)
        le32(24, sampleRate)
        le32(28, sampleRate * 2)
        le16(32, 2)
        le16(34, 16)
        ascii(36, "data")
        le32(40, dataBytes)
        var o = 44
        for (s in samples) {
            out[o++] = s.toByte()
            out[o++] = (s.toInt() shr 8).toByte()
        }
        return out
    }

    fun readWav(bytes: ByteArray): WavPcm {
        require(bytes.size >= 44) { "WAV too small" }
        fun u32(at: Int) = (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
        fun u16(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
        var rate = 44100
        var channels = 1
        var bits = 16
        var dataAt = -1
        var dataLen = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = u32(i + 4)
            val body = i + 8
            when (id) {
                "fmt " -> {
                    channels = u16(body + 2)
                    rate = u32(body + 4)
                    bits = u16(body + 14)
                }
                "data" -> {
                    dataAt = body
                    dataLen = size
                }
            }
            i = body + size + (size and 1)
            if (id == "data") break
        }
        require(dataAt >= 0 && bits == 16) { "Need 16-bit PCM WAV" }
        val frames = dataLen / (2 * channels)
        val samples = ShortArray(frames)
        var p = dataAt
        for (f in 0 until frames) {
            val sample = u16(p).toShort()
            samples[f] = sample
            p += 2 * channels
        }
        return WavPcm(rate, samples)
    }

    private fun trim(freq: Double): String = if (freq >= 100.0) freq.toInt().toString() else freq.toString()

    private fun parseStamp(text: String): Long? = runCatching {
        LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
