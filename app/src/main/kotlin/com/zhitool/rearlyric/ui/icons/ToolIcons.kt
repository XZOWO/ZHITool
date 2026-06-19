/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 歌词页底栏图标：自绘填充式八分音符（♫，两符头 + 横梁 + 符干），
 * 仿底栏 Material Rounded 填充图标的视觉重量。用 [PathParser] 解析 SVG path，
 * 填充色用黑、由 `Icon(tint = …)` 着色。
 */
val LyricNoteIcon: ImageVector by lazy {
    // 在 24×24 视口内放大字形（约以中心 1.34 倍缩放），让它与底栏其它图标视觉等大。
    val path = buildString {
        append("M8.51,4.75 L20.86,1.53 L20.86,4.75 L8.51,7.97 Z ")        // 横梁
        append("M8.51,4.75 L10.52,4.22 L10.52,18.98 L8.51,18.98 Z ")      // 左符干
        append("M18.84,2.20 L20.86,1.53 L20.86,16.03 L18.84,16.03 Z ")    // 右符干
        append("M2.87,18.98 a3.49,3.49 0 1,0 6.98,0 a3.49,3.49 0 1,0 -6.98,0 Z ")  // 左符头
        append("M13.74,16.03 a3.49,3.49 0 1,0 6.98,0 a3.49,3.49 0 1,0 -6.98,0 Z")  // 右符头
    }
    ImageVector.Builder(
        name = "LyricNote",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
