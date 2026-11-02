/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

/** Streaming linear convolution via overlap-save. */
class OverlapSaveFilter(fir: DoubleArray) {
    private val m = fir.size.coerceAtLeast(1)
    private val n: Int
    private val hop: Int
    private val hRe: DoubleArray
    private val hIm: DoubleArray
    private val inBuf: DoubleArray
    private var fill = 0

    init {
        var p = 1
        while (p < m * 2) p = p shl 1
        n = p
        hop = n - m + 1
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in fir.indices) re[i] = fir[i]
        Fft.forward(re, im)
        hRe = re
        hIm = im
        inBuf = DoubleArray(n)
    }

    fun process(input: DoubleArray): DoubleArray {
        if (input.isEmpty()) return DoubleArray(0)
        val out = ArrayList<Double>(input.size)
        var pos = 0
        while (pos < input.size) {
            val take = minOf(hop - fill, input.size - pos)
            val dest = m - 1 + fill
            for (i in 0 until take) inBuf[dest + i] = input[pos + i]
            fill += take
            pos += take
            if (fill == hop) {
                convolveBlock(out)
                val keep = m - 1
                for (i in 0 until keep) inBuf[i] = inBuf[hop + i]
                fill = 0
            }
        }
        return out.toDoubleArray()
    }

    fun reset() {
        inBuf.fill(0.0)
        fill = 0
    }

    private fun convolveBlock(out: ArrayList<Double>) {
        val re = inBuf.copyOf()
        val im = DoubleArray(n)
        Fft.forward(re, im)
        for (k in 0 until n) {
            val r = re[k] * hRe[k] - im[k] * hIm[k]
            val ii = re[k] * hIm[k] + im[k] * hRe[k]
            re[k] = r
            im[k] = ii
        }
        Fft.inverse(re, im)
        for (i in (m - 1) until n) out.add(re[i])
    }
}
