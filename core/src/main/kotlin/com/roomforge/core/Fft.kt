/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex DFT. Inverse scales by 1/N. */
object Fft {
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    fun inverse(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = true)

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n >= 2 && n and (n - 1) == 0) { "FFT length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            val half = len ushr 1
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val idx = i + k
                    val idx2 = idx + half
                    val vRe = re[idx2] * wRe - im[idx2] * wIm
                    val vIm = re[idx2] * wIm + im[idx2] * wRe
                    val uRe = re[idx]
                    val uIm = im[idx]
                    re[idx] = uRe + vRe
                    im[idx] = uIm + vIm
                    re[idx2] = uRe - vRe
                    im[idx2] = uIm - vIm
                    val nextRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1.0 / n
            for (i in 0 until n) {
                re[i] *= inv
                im[i] *= inv
            }
        }
    }
}
