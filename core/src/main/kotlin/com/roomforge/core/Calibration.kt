/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

data class MicCalFile(
    val points: List<Pair<Double, Double>>,
    val sensDb: Double?,
    val serial: String?
)

object CalibrationFiles {
    private val sensRegex = Regex("(?i)sens\\s*factor\\s*=\\s*([+-]?[0-9]*\\.?[0-9]+)")
    private val serialRegex = Regex("(?i)SERNO:\\s*([A-Za-z0-9_-]+)")

    /** Dayton iMM-6 and miniDSP PMIK / UMIK text exports: frequency Hz and response dB. */
    fun parse(text: String): MicCalFile {
        var sens: Double? = null
        var serial: String? = null
        val points = ArrayList<Pair<Double, Double>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            sensRegex.find(line)?.let { sens = it.groupValues[1].toDoubleOrNull() }
            serialRegex.find(line)?.let { serial = it.groupValues[1] }
            if (line.startsWith("*") || line.startsWith("#") || line.startsWith(";") || line.startsWith("\"")) {
                continue
            }
            val parts = line.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
            if (parts.size < 2) continue
            val freq = parts[0].toDoubleOrNull() ?: continue
            val db = parts[1].toDoubleOrNull() ?: continue
            if (freq > 0.0) points.add(freq to db)
        }
        points.sortBy { it.first }
        return MicCalFile(points, sens, serial)
    }

    /**
     * Power-average the mic curve into the 31 ISO 1/3-octave bands, make it
     * relative to 1 kHz, and convert a response into a correction (negated)
     * unless [treatAsResponse] is false (values are already corrections).
     */
    fun toThirdOctave(file: MicCalFile, treatAsResponse: Boolean = true): DoubleArray {
        val bands = IsoBands.octave3
        val response = DoubleArray(bands.size) { Double.NaN }
        for (i in bands.indices) {
            val band = bands[i]
            var sum = 0.0
            var count = 0
            for ((freq, db) in file.points) {
                if (freq >= band.lowHz && freq < band.highHz) {
                    sum += 10.0.pow(db / 10.0)
                    count++
                }
            }
            if (count > 0) response[i] = 10.0 * log10(sum / count)
        }
        interpolate(bands, response)
        val refIndex = bands.indexOfFirst { abs(it.centerHz - 1000.0) < 1.0 }.coerceAtLeast(0)
        val ref = response[refIndex]
        return DoubleArray(bands.size) { i ->
            val relative = response[i] - ref
            if (treatAsResponse) -relative else relative
        }
    }

    private fun interpolate(bands: List<Band>, values: DoubleArray) {
        val known = values.indices.filter { !values[it].isNaN() }
        if (known.isEmpty()) {
            values.fill(0.0)
            return
        }
        for (i in values.indices) {
            if (!values[i].isNaN()) continue
            val prev = known.lastOrNull { it < i }
            val next = known.firstOrNull { it > i }
            values[i] = when {
                prev == null && next != null -> values[next]
                next == null && prev != null -> values[prev]
                prev != null && next != null -> {
                    val span = next - prev
                    values[prev] + (values[next] - values[prev]) * (i - prev).toDouble() / span
                }
                else -> 0.0
            }
        }
    }
}
