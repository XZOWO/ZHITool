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

/**
 * 背屏背景：默认（封面取色渐变 + 游走高光）/ 律动（默认之上亮光随音乐能量波动）/
 * 声谱（默认之上叠底部频谱柱，随音乐 FFT 律动）。律动/声谱靠 root 内录（audio policy loopback）监听音频。
 */
enum class RearBackground { DEFAULT, PULSE, SPECTRUM }

/**
 * 律动恢复速度：律动/声谱回落（从峰值降回静止）的平滑快慢。起拍仍即时跟手，只让回落更柔和更美观。
 * 极快→极慢共五档，默认中。
 */
enum class RhythmDecay { VERY_FAST, FAST, MEDIUM, SLOW, VERY_SLOW }

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
    /** 逐字抬起：未唱文字下沉、唱到时非线性抬起回正；关闭则未唱/已唱文字保持同一水平线，只看颜色/高亮变化。 */
    val sinkAnimation: Boolean = true,
    /** 歌词动画（仅 SuperLyric 单句模式）：当前句进场效果，默认随机升起。 */
    val lyricAnimation: LyricAnimation = LyricAnimation.RANDOM_RISE,
    /** 电池显示模式：充电时显示（默认）/ 一直显示 / 不显示。在小锁位置画 iOS 式电池。 */
    val batteryMode: BatteryMode = BatteryMode.WHEN_CHARGING,
    /** 播放时充电动画（默认开）：控制播放 + 充电时歌词背后的上升液体波浪是否显示。 */
    val chargeWave: Boolean = true,
    // ---- 音乐律动 ----
    /** 背屏背景模式：默认 / 律动 / 声谱（默认 [RearBackground.PULSE]）。 */
    val background: RearBackground = RearBackground.PULSE,
    /** 律动强度 0..100（默认 20）：放大音频反应灵敏度，越大波动/频谱越夸张。 */
    val rhythmIntensity: Int = 20,
    /** 律动恢复速度（默认慢）：律动/声谱回落的平滑快慢，越慢回落越柔和。 */
    val rhythmDecay: RhythmDecay = RhythmDecay.SLOW,
    /** 声谱高度缩放（百分比，默认 60）：满格频段时柱高=屏高×该比例，可超 100（不限制，封顶屏高）。 */
    val spectrumHeight: Int = 60,
    /** 律动两团高光增益（百分比，用户可调）：低音(打击/动次打次,默认300)/非低音(谐波,默认500)各自反应强度。 */
    val glowPercGain: Int = 300,
    val glowHarmGain: Int = 500,
    /**
     * 三个各自独立的音乐律动开关（均默认开），与 [background] 互不相干；任一开启即额外启动 root 内录
     * （耗电/CPU），三个都关才完全不启动内录：
     *  · [lyricGlow]  歌词发光：当前句高亮文字随鼓点叠加光晕（强度 [lyricGlowIntensity]）。
     *  · [lyricRhythm] 歌词律动：整屏歌词随节奏整体缩放（锚定当前句、不偏移，幅度 [uiRhythmIntensity]）。
     *  · [controlRhythm] 空间律动：封面 + 控制面板按钮随节奏缩放（幅度 [uiRhythmIntensity]）。
     * [uiRhythmIntensity]/[uiRhythmDecay] 是缩放类（歌词律动+空间律动）共用的强度/回落；
     * 发光强度单列 [lyricGlowIntensity]；低音-非低音增益（[glowPercGain]/[glowHarmGain]）与背景共用。
     */
    val lyricGlow: Boolean = true,
    val lyricRhythm: Boolean = true,
    val controlRhythm: Boolean = true,
    /** 缩放类律动（歌词律动 + 空间律动）强度 0..100（默认 20），独立于背景的 [rhythmIntensity]。 */
    val uiRhythmIntensity: Int = 20,
    /**
     * 缩放类律动回落速度（默认慢），独立于背景的 [rhythmDecay]、映射范围整体更慢——同为"慢"，
     * 这里比背景的"慢"更柔（歌词/控件缩放对高频抖动比背景模糊光斑敏感得多，需要更慢才不显颤）。
     */
    val uiRhythmDecay: RhythmDecay = RhythmDecay.SLOW,
    /** 歌词发光强度 0..300%（默认 300）：只影响发光光晕的强弱，不影响封面/按钮/歌词的缩放幅度。 */
    val lyricGlowIntensity: Int = 300,
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
