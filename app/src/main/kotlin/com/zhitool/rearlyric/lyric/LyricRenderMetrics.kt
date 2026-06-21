package com.zhitool.rearlyric.lyric

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 背屏渲染端回报的实时度量，供配置页显示「当前实际值」作为默认（而非从 0 开始）。
 * 目前仅歌词字号是按内容宽自动适配、设备相关，需要回报；小锁半径/细线长度是固定 dp 常量，配置默认值即可。
 */
object LyricRenderMetrics {
    /** FullLyricView 当前自动计算的歌词字号（px，未含用户绝对覆盖）；0=尚未渲染过。 */
    val autoTextSizePx = MutableStateFlow(0)
}
