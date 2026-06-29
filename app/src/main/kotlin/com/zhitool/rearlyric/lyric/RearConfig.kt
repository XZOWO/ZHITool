package com.zhitool.rearlyric.lyric

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 封面位置。 */
enum class CoverPosition { LEFT, NONE, RIGHT }

/** 封面样式。 */
enum class CoverShape { SQUARE, CIRCLE, CIRCLE_ROTATE }

/** 背屏歌词刷新帧率。 */
enum class LyricFrameRate { FPS_120, FPS_60 }

/** 文字配色模式：默认白 / 提取封面颜色（单色）/ 提取封面渐变色（横向渐变，参照词幕）。 */
enum class TextColorMode { DEFAULT, COVER, COVER_GRADIENT }

/** 电池显示：不显示 / 充电时显示（默认）/ 一直显示。在小锁位置画 iOS 式电池（数字，充电未满时带闪电）。 */
enum class BatteryMode { NONE, WHEN_CHARGING, ALWAYS }

/** 歌词数据源：词幕（lyricon 生态，整首逐字）/ SuperLyric（实时逐句广播）。二选一，默认词幕。 */
enum class LyricSource { LYRICON, SUPERLYRIC }

/** 歌词动画（目前仅 SuperLyric 单句模式生效）：无 / 随机升起（默认，逐字/逐词随机时序从底部升起）。 */
enum class LyricAnimation { NONE, RANDOM_RISE }

/** 背屏渲染配置（仅全量歌词模式）。 */
data class RearConfig(
    val cover: CoverPosition = CoverPosition.LEFT,
    val coverShape: CoverShape = CoverShape.SQUARE,
    val frameRate: LyricFrameRate = LyricFrameRate.FPS_120,
    val dynamicBackground: Boolean = true,
    val textColorMode: TextColorMode = TextColorMode.DEFAULT,
    val showSecondary: Boolean = true,
    val showTranslation: Boolean = true,
    val showRoma: Boolean = true,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val fontWeight: Int = 700,
    /** 渐变高亮：高亮扫过的前沿用渐变软边而非硬切。 */
    val gradientProgress: Boolean = true,
    /** 相对进度：当前词/字内按时间平滑扫过（关闭则整词到点即整体点亮）。 */
    val relativeProgress: Boolean = true,
    /** 相对高亮：汉字逐字点亮（关闭则整词为单位，较粗）。 */
    val relativeHighlight: Boolean = true,
    /** 模拟逐字：歌曲只有整句（无逐字时间）时，按时长假装逐字扫过；关闭则整句到点一起切（不假装逐字）。 */
    val simulateWordTiming: Boolean = true,
    /** 歌词动画（仅 SuperLyric 单句模式）：当前句进场效果，默认随机升起。 */
    val lyricAnimation: LyricAnimation = LyricAnimation.RANDOM_RISE,
    /** 电池显示模式：充电时显示（默认）/ 一直显示 / 不显示。在小锁位置画 iOS 式电池。 */
    val batteryMode: BatteryMode = BatteryMode.WHEN_CHARGING,
    /** 播放时充电动画（默认开）：控制播放 + 充电时歌词背后的上升液体波浪是否显示。 */
    val chargeWave: Boolean = true,
    // ---- 微调 ----
    /** 左安全区微调（步进，默认 0）：+1 边界右移（歌词右缩）、-1 左移。每步 1dp。 */
    val safeAreaLeft: Int = 0,
    /** 右安全区微调（步进，默认 0）：+1 边界右移（歌词右扩）、-1 左移。每步 1dp。 */
    val safeAreaRight: Int = 0,
    /** 歌词中文文字大小（绝对像素 px，默认 59）；英文比中文大 4px。0=自动按内容宽适配九字。 */
    val lyricTextSize: Int = 59,
    /** 解锁小锁外圈半径（dp，默认 14）；锁体与外圈同步缩放。 */
    val lockSize: Int = 14,
    /** 解锁小锁左右微调（步进，默认 0=居中基准）：+1 右、-1 左。每步约 2dp。 */
    val lockOffset: Int = 0,
    /** 拖动时间（含细线）左右微调（步进，默认 0=居中基准）：+1 右、-1 左。每步约 2dp。 */
    val timeOffset: Int = 0,
    /** 拖动时间细线长度（绝对 dp，默认 32）；左端固定，仅向右伸缩。 */
    val timeLineLength: Int = 32,
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
 * 歌词投放主开关：true=允许（播放/切歌/暂停/启动时自动把歌词投背屏，默认）；false=停止（不自动投、收回当前歌词）。
 * 主页"启动/停止歌词投放"按钮、通知按钮、以及"启动/停止所有投放"共用此状态，[ConfigStore] 落盘。
 */
object ProjectionState {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    val current: Boolean get() = _enabled.value

    fun update(value: Boolean) {
        _enabled.value = value
    }
}

/**
 * 工具投放开关：true=允许充电动画/背屏通知投到背屏（默认）；false=不投并收回当前。
 * 仅由主页"启动/停止所有投放"统一控制（与 [ProjectionState] 一起翻），[ConfigStore] 落盘。
 */
object ToolProjectionState {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    val current: Boolean get() = _enabled.value

    fun update(value: Boolean) {
        _enabled.value = value
    }
}

/**
 * 歌词数据源选择（全局，[ConfigStore] 落盘）：[LyricSource.LYRICON] 走词幕订阅端；
 * [LyricSource.SUPERLYRIC] 走 SuperLyric 实时接收。[LyricService] 据此启停对应来源。
 */
object LyricSourceState {
    private val _flow = MutableStateFlow(LyricSource.LYRICON)
    val flow: StateFlow<LyricSource> = _flow

    val current: LyricSource get() = _flow.value

    fun update(value: LyricSource) {
        _flow.value = value
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
