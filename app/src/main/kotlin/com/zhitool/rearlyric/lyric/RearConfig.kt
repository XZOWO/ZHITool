package com.zhitool.rearlyric.lyric

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 歌词显示模式：歌曲信息+歌词（默认布局），或全量歌词（Apple Music 式滚动）。 */
enum class LyricDisplayMode { INFO_LYRIC, FULL_LYRIC }

/** 歌词对齐方式。 */
enum class LyricAlign { LEFT, CENTER, RIGHT }

/** 映射到词幕引擎的 horizontalAlign 整数：0=左 1=中 2=右。 */
fun LyricAlign.toAlignInt(): Int = when (this) {
    LyricAlign.LEFT -> 0
    LyricAlign.CENTER -> 1
    LyricAlign.RIGHT -> 2
}

/** 封面位置。 */
enum class CoverPosition { LEFT, NONE, RIGHT }

/** 封面样式。 */
enum class CoverShape { SQUARE, CIRCLE, CIRCLE_ROTATE }

/** 背屏歌词刷新帧率。 */
enum class LyricFrameRate { FPS_120, FPS_60 }

/** 文字配色模式：默认白 / 提取封面颜色（单色）/ 提取封面渐变色（横向渐变，参照词幕）。 */
enum class TextColorMode { DEFAULT, COVER, COVER_GRADIENT }

/** 背屏渲染配置。 */
data class RearConfig(
    val displayMode: LyricDisplayMode = LyricDisplayMode.FULL_LYRIC,
    /** 歌词对齐（仅"歌曲信息+歌词"模式；全量模式跟随歌词数据自身对齐）。 */
    val align: LyricAlign = LyricAlign.CENTER,
    val cover: CoverPosition = CoverPosition.LEFT,
    val coverShape: CoverShape = CoverShape.SQUARE,
    val frameRate: LyricFrameRate = LyricFrameRate.FPS_120,
    val dynamicBackground: Boolean = true,
    val textColorMode: TextColorMode = TextColorMode.DEFAULT,
    val showSecondary: Boolean = true,
    val showTranslation: Boolean = true,
    val showRoma: Boolean = true,
    /** 主歌词字号（仅"歌曲信息+歌词"模式；全量模式按一行九个汉字自适应）。 */
    val fontSize: Int = 60,
    /** 副歌词相对主歌词比例（仅"歌曲信息+歌词"模式）。 */
    val secondaryScale: Float = 0.86f,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val fontWeight: Int = 700,
    /** 渐变高亮：高亮扫过的前沿用渐变软边而非硬切。 */
    val gradientProgress: Boolean = true,
    /** 相对进度：当前词/字内按时间平滑扫过（关闭则整词到点即整体点亮）。 */
    val relativeProgress: Boolean = true,
    /** 相对高亮：汉字逐字点亮（关闭则整词为单位，较粗）。 */
    val relativeHighlight: Boolean = true,
)

/**
 * 全局配置持有器：可观察(背屏据此实时刷新) + 由 [ConfigStore] 落盘/读取。
 */
object RearConfigState {
    private val _flow = MutableStateFlow(RearConfig())
    val flow: StateFlow<RearConfig> = _flow

    val current: RearConfig get() = _flow.value

    fun update(cfg: RearConfig) {
        _flow.value = cfg
    }
}

/** 包级配置：某播放器使用单独样式。 */
data class PackageStyle(
    val packageName: String,
    val label: String,
    val config: RearConfig = RearConfig(),
)

object PackageStyleState {
    private val _flow = MutableStateFlow<Map<String, PackageStyle>>(emptyMap())
    val flow: StateFlow<Map<String, PackageStyle>> = _flow

    val current: Map<String, PackageStyle> get() = _flow.value

    fun update(value: Map<String, PackageStyle>) {
        _flow.value = value
    }
}

/**
 * 投放主开关：true=允许投放（含播放音乐自动投背屏，默认）；false=停止投放（不自动投、收回当前投放）。
 * 主页"停止投放/开始投放"按钮与通知按钮共用此状态，[ConfigStore] 落盘。
 */
object ProjectionState {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    val current: Boolean get() = _enabled.value

    fun update(value: Boolean) {
        _enabled.value = value
    }
}

/** 包配置：只监听选中的音乐应用（按播放器包名过滤）。空集合 + 关闭 = 全部监听。 */
data class AppFilter(
    val onlySelected: Boolean = false,
    val selectedApps: Set<String> = emptySet(),
) {
    /** 给定播放器包名是否允许投放。 */
    fun allows(playerPackage: String?): Boolean =
        !onlySelected || (playerPackage != null && playerPackage in selectedApps)
}

object AppFilterState {
    private val _flow = MutableStateFlow(AppFilter())
    val flow: StateFlow<AppFilter> = _flow

    val current: AppFilter get() = _flow.value

    fun update(f: AppFilter) {
        _flow.value = f
    }
}
