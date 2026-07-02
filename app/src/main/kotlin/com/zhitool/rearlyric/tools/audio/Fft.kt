/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.audio

import kotlin.math.cos
import kotlin.math.sin

/** 就地基-2 Cooley–Tukey FFT（长度须为 2 的幂）。内录 PCM 自算频谱用。 */
object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n < 2 || n and (n - 1) != 0) return
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val ik = i + k
                    val ikh = ik + half
                    val bR = re[ikh] * curR - im[ikh] * curI
                    val bI = re[ikh] * curI + im[ikh] * curR
                    val aR = re[ik]
                    val aI = im[ik]
                    re[ik] = aR + bR
                    im[ik] = aI + bI
                    re[ikh] = aR - bR
                    im[ikh] = aI - bI
                    val nCurR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nCurR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
