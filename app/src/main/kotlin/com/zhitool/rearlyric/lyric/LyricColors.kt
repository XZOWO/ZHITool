package com.zhitool.rearlyric.lyric

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette

/**
 * 背屏配色：从封面取色（参考词幕的歌词取色思路）。
 *
 * - [background] / [backgroundEnd]：背景渐变两端（取自封面暗色调）。
 * - [highlight]：已唱文字高亮色（取自封面鲜明色）。
 * - [dim]：未唱文字暗色。
 */
data class LyricColors(
    val background: Color,
    val backgroundEnd: Color,
    val highlight: Color,
    val dim: Color,
    /** 高亮渐变色组（≥2 色时按词幕方式建横向 LinearGradient）。 */
    val highlightGradient: List<Color> = listOf(highlight),
) {
    companion object {
        val Default = LyricColors(
            background = Color(0xFF12151C),
            backgroundEnd = Color(0xFF05070B),
            highlight = Color(0xFFFFFFFF),
            dim = Color(0x59FFFFFF),
            highlightGradient = listOf(Color(0xFFFFFFFF), Color(0xFFCFE0FF)),
        )

        /** 从封面字节取色；失败回退 [Default]。 */
        fun fromCover(bytes: ByteArray?): LyricColors {
            if (bytes == null || bytes.isEmpty()) return Default
            return runCatching {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return Default
                val palette = Palette.from(bmp).maximumColorCount(16).generate()

                val vibrant = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.dominantSwatch
                val dark = palette.darkMutedSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.dominantSwatch

                val hi = vibrant?.rgb?.let { Color(it) } ?: Color.White
                val bg = dark?.rgb?.let { Color(it) } ?: Color(0xFF12151C)
                val highlight = hi.lighten(0.15f)

                // 渐变色组：取封面里多个鲜明色调（参照词幕的提取封面渐变色），不足两色则用主高亮色衍生。
                val gradientCandidates = listOfNotNull(
                    palette.vibrantSwatch,
                    palette.lightVibrantSwatch,
                    palette.mutedSwatch,
                    palette.darkVibrantSwatch,
                    palette.dominantSwatch,
                ).map { Color(it.rgb) }.distinct().take(3)
                val gradient = if (gradientCandidates.size >= 2) {
                    gradientCandidates.map { it.lighten(0.18f) }
                } else {
                    listOf(highlight, highlight.lighten(0.35f))
                }

                LyricColors(
                    background = bg.darken(0.45f),
                    backgroundEnd = bg.darken(0.78f),
                    highlight = highlight,
                    dim = Color.White.copy(alpha = 0.32f),
                    highlightGradient = gradient,
                )
            }.getOrDefault(Default)
        }

        private fun Color.darken(factor: Float): Color =
            Color(red * (1 - factor), green * (1 - factor), blue * (1 - factor), 1f)

        private fun Color.lighten(factor: Float): Color =
            Color(
                red + (1 - red) * factor,
                green + (1 - green) * factor,
                blue + (1 - blue) * factor,
                1f,
            )
    }
}
