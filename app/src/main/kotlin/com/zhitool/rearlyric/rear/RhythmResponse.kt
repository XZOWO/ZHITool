/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.rear

/**
 * 普通歌曲保持原有 0..0.55 反应；超过后进入软肩，给强鼓点/复杂编曲保留动态余量。
 *
 * 必须在原始平滑能量乘完低音/非低音及用户强度后调用。与直接 `coerceIn(0f, 1f)`
 * 相比，强输入只会逐渐接近 1，不会大段时间硬贴上限；静音仍严格为 0。
 */
internal fun softRhythmEnergy(value: Float): Float {
    if (value.isNaN() || value <= 0f) return 0f
    if (value == Float.POSITIVE_INFINITY) return 1f
    if (value <= RHYTHM_LINEAR_LIMIT) return value
    val over = value - RHYTHM_LINEAR_LIMIT
    return RHYTHM_LINEAR_LIMIT +
        (1f - RHYTHM_LINEAR_LIMIT) * over / (over + RHYTHM_SOFT_SHOULDER)
}

private const val RHYTHM_LINEAR_LIMIT = 0.55f
private const val RHYTHM_SOFT_SHOULDER = 1.25f
