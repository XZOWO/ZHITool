package com.zhitool.rearlyric.rear

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.zhitool.rearlyric.lyric.CoverPosition
import com.zhitool.rearlyric.lyric.CoverShape
import com.zhitool.rearlyric.lyric.LyricAnimation
import com.zhitool.rearlyric.lyric.LyricColors
import com.zhitool.rearlyric.lyric.LyricRenderMetrics
import com.zhitool.rearlyric.lyric.RearConfig
import com.zhitool.rearlyric.lyric.TextColorMode
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 固定字号：按一行排满九个汉字计算。 */
private const val CHARS_PER_LINE = 9
/** 英文字号比中文大这么多像素（中英文不同字号，视觉更均衡）。 */
private const val LATIN_EXTRA_PX = 4f
/** 行内换行的行距倍数与句间距（相对字号）。 */
private const val LINE_SPACING_MULT = 1.04f
private const val LINE_GAP_EM = 0.55f
/** 句间切换动画时长（非线性 ease-out）。 */
private const val SCROLL_DURATION_MS = 620f
/** 换歌整页上滑过渡：时长 + 位移（相对视图高度，旧歌上滑淡出、新歌从下方顶上来）。 */
private const val SONG_SWITCH_MS = 500f
private const val SONG_SWITCH_SHIFT_FRAC = 0.45f
/** 切歌后这么久内强制按"首句前"渲染（新歌默认从头、显示大封面），之后再采用新歌真实进度。 */
private const val SONG_SWITCH_HOLD_MS = 300L
/**
 * 换歌封面过渡（独立于整页底板上滑）：旧封面飞出/新封面飞入时长、大封面飞出缩放系数、
 * 以及等新封面加载的最长等待（超时就用当前封面飞入，避免一直空着）。
 */
private const val COVER_FLY_MS = 540f
private const val COVER_EXIT_SCALE = 0.8f
private const val COVER_ENTER_MAX_WAIT_MS = 3000L
/** 中心句上下各绘制 3 句可见 + 1 句备绘，滚动时边缘不再看着加载。 */
private const val DRAW_RADIUS = 4
/** 渐隐范围放宽到 3.6 个行位：边缘句透明度约为收紧前的 80%。 */
private const val FADE_SLOTS = 3.6f
private const val FADE_EXPONENT = 1.45f
/** 已唱句"叠加白色"的强度随距离上升的指数（<1 让稍远即偏白，到隐藏边缘恰好全白）。 */
private const val WHITE_FADE_EXPONENT = 0.8f
/** 默认白字模式：已读文字透明度（<1 透出背景色，不是死白）。仅 DEFAULT 配色生效。 */
private const val READ_TEXT_ALPHA = 0.82f
/** 渐进模糊：最大半径（相对字号）与随距离增长的指数（同步调柔）。 */
private const val BLUR_MAX_EM = 0.26f
private const val BLUR_EXPONENT = 1.6f
/** 当前句未唱部分的基色透明度（对齐单句模式的 argb 116）。 */
private const val BASE_DIM_ALPHA = 116f
/** 未唱文字下沉量（相对字号）与唱到时回正动画的最长时长。 */
private const val SINK_EM = 0.075f
private const val LIFT_MS = 320L
/** 副歌词渐显/收起动画时长、字号比例、与主歌词间距、透明度、整块缩小量。 */
private const val REVEAL_MS = 600f
private const val SEC_EM = 0.66f
private const val SEC_GAP_EM = 0.18f
private const val SEC_ALPHA = 0.75f
/** 副歌词渐变进度的基色/高亮透明度。 */
private const val SEC_BASE_ALPHA = 110f
private const val SEC_HI_ALPHA = 235f
private const val BLOCK_SHRINK = 0.06f
/** 当前演唱句放大突出倍率与放大/缩小动画时长（墙钟，非线性 easeOut）。仅全量歌词。 */
private const val CURRENT_ZOOM = 0.03f
private const val ZOOM_MS = 300f
/** 副句"演唱前提前量"：带逐字时间的和声在自己开口前这么久才顶入显示。 */
private const val SEC_LEAD_MS = 300L
/** 圆点前歌名-作者名：字号比例 / 与歌词合计最多行数 / 呼吸动画幅度（弱）。 */
private const val TITLE_EM = 0.75f
private const val INTRO_MAX_ROWS = 6
private const val TITLE_PULSE_ALPHA_DIP = 0.18f
private const val TITLE_PULSE_BLUR_EM = 0.06f
/** 首句前圆点：呼吸周期 / 首句前多少 ms 开始缩小消失 / 前奏过短不展示。 */
private const val DOT_PULSE_PERIOD_MS = 2400.0
private const val DOT_EXIT_LEAD_MS = 500L
private const val DOT_MIN_INTRO_MS = 1200L

/** 长按解锁：蓄力时长（圆形进度填满即解锁）、移动取消阈值、解锁后空闲自动回锁、小锁开合动画时长。 */
private const val UNLOCK_HOLD_MS = 1000L
private const val UNLOCK_MOVE_CANCEL_DP = 16f
private const val UNLOCK_IDLE_RELOCK_MS = 6000L
private const val LOCK_ANIM_MS = 420f
/** seek 后本地进度覆盖：直播进度回到附近这个窗口内即认为已追上(撤销覆盖)；最长覆盖时长(防卡死)。 */
private const val SEEK_SETTLE_MS = 1200L
private const val SEEK_HOLD_MAX_MS = 4000L
/** 拖动惯性滑动：起飞最小速度(px/s)、指数摩擦系数、停止阈值(px/s)。 */
private const val FLING_MIN_VELOCITY = 120f
private const val FLING_FRICTION = 3.2f
private const val FLING_STOP_VELOCITY = 36f

/** 首句前的封面卡片：封面相对内容宽的比例与上下限（dp，适度调整）。 */
private const val INTRO_COVER_WIDTH_FRAC = 0.27f
private const val INTRO_COVER_MIN_DP = 70f
private const val INTRO_COVER_MAX_DP = 116f
/** 引导卡：封面与文字间距、歌名/歌手字号比例、歌名行间距、歌手透明度。 */
private const val INTRO_GAP_EM = 0.5f
private const val INTRO_NAME_EM = 0.92f
private const val INTRO_ARTIST_EM = 0.6f
private const val INTRO_NAME_ARTIST_GAP_DP = 4f
private const val INTRO_ARTIST_ALPHA = 0.85f
/** 引导卡整体距安全区顶部的留白（相对内容高度），尽量上靠贴安全区。 */
private const val INTRO_TOP_FRAC = 0.04f
/** 角落停靠的小封面基准尺寸（相对字号）、贴角边距（dp，越大越往左下；防止超出屏幕/圆角裁切）、与时间间距、时间字号（相对小封面）。 */
private const val DOCK_COVER_EM = 0.96f
private const val DOCK_MARGIN_DP = 10f
private const val CLOCK_GAP_DP = 7f
private const val CLOCK_TEXT_FRAC = 0.62f
/** 角落小封面：播放时放大到基准的 1.1 倍、暂停回 1 倍（非线性 easeOut）。 */
private const val DOCK_COVER_PLAY_SCALE = 1.1f
private const val DOCK_SCALE_MS = 500f
/** 封面缩到角落的动画（非线性）与停靠完成后文字（歌名/时间）淡入时长。 */
private const val DOCK_MS = 640f
private const val CLOCK_FADE_MS = 280f
/** 回退到大封面的去抖：进度回到首句前需持续这么久才回退（滤掉切歌边界 1~2 帧的瞬时抖动，避免闪一下大封面）。 */
private const val DOCK_REVERT_DEBOUNCE_MS = 200L
/** 停靠后先显示"歌名-歌手"这么久再切到时间；切换淡入淡出时长；歌名过长时跑马灯速度/循环间隔。 */
private const val NAME_SHOW_MS = 3000L
private const val NAME_TIME_SWITCH_MS = 280f
private const val MARQUEE_SPEED_DP = 36f
private const val MARQUEE_GAP_DP = 44f
/** 封面单击/双击：双击进控制面板的判定窗口；单击弹"双击打开控制页"提示的总显示时长与提示文案。 */
private const val COVER_DOUBLE_TAP_MS = 280L
private const val COVER_HINT_MS = 2600f
private const val COVER_HINT_TEXT = "双击打开控制页"
/** 旋转封面每圈用时（秒），与单句模式一致。 */
private const val COVER_ROTATION_SECONDS = 16f
/** 渐变高亮：前沿羽化宽度（相对字号）。 */
private const val GRADIENT_SOFT_EM = 0.9f
/** 渐变高亮 alpha 蒙版色（仅 alpha 有意义：不透明 → 透明）。 */
private val HIGHLIGHT_MASK_COLORS = intArrayOf(-0x1, 0x00FFFFFF)

/** Apple Music：歌词自带逐句居左/居右（对唱），直接采用。 */
private const val APPLE_MUSIC_PKG = "com.apple.android.music"

// ---- SuperLyric 单句模式 ----
/** 当前句字号上限按「一行 7 汉字」占满内容宽算（无下限，再按行数/高度/超长词往下缩）。 */
private const val SL_CHARS_PER_LINE = 7
private const val SL_MAX_ROWS = 3
/** 「随机升起」：单 token 升起时长（ms，偏快）。 */
private const val SL_RISE_DUR_MS = 280f
/** 「随机升起」：各 token 起始延迟的随机上限（ms，制造随机时序）。 */
private const val SL_RISE_STAGGER_MS = 420L
/** 「随机升起」：起始下移量（相对行高），从下方升到位。 */
private const val SL_RISE_DIST_FRAC = 1.0f
/** 行高系数（基于字形盒高 descent-ascent，去掉字体多余行距，三行更紧凑不溢出）。 */
private const val SL_ROW_SPACING = 1.05f
/** 下半区竖向可用比例（留上下边距，避免末行贴边/被截断）。 */
private const val SL_BOTTOM_FILL = 0.9f

/**
 * 全量歌词模式（Apple Music 式）：固定字号、逐词排版，对齐跟随歌词数据
 * （Apple Music 按句居左/居右，副歌词跟随；其它来源居中），当前句带渐变进度
 * （汉字逐字、其它语言按词：未唱下沉、唱到时非线性回正），副歌词渐显把主歌词顶起；
 * 上下句按离中心距离均匀渐隐叠加渐进模糊（当前句不参与渐显，避免切句卡顿感）；
 * 首句前展示歌名-作者名（随圆点同频弱呼吸模糊）与三个呼吸圆点。
 */
internal class FullLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
    }
    private val secPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blurCache = HashMap<Int, BlurMaskFilter>()

    /** 引导卡的歌名/歌手与角落时间文字（独立字号，不随歌词字号变化）。 */
    private val introNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(6f), 0f, dp(1.5f), Color.argb(112, 0, 0, 0))
    }
    private val introArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(96, 0, 0, 0))
    }
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(110, 0, 0, 0))
    }
    /** SuperLyric 单句模式：当前句独立字号（按 7 汉字+1 空格/行算，不随歌词页字号）。 */
    private val slPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
    }
    /** 渐变高亮前沿羽化：在 saveLayer 内用 DST_IN 横向 alpha 渐变蒙版裁出软边。 */
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstInXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    /** 封面绘制（圆角/圆形裁剪 + 旋转）。 */
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val coverClipPath = Path()
    private val coverSrc = Rect()
    private val coverDst = RectF()

    private var song: Song? = null
    private var positionMs = 0L
    private var config: RearConfig = RearConfig()
    private var colors: LyricColors = LyricColors.Default
    private var playerPackage: String? = null
    private var coverBitmap: Bitmap? = null
    private var playing: Boolean = false

    /** 引导卡歌名/歌手布局（首句前展示）。 */
    private var introNameLayout: StaticLayout? = null
    private var introArtistLayout: StaticLayout? = null

    /** 封面旋转角累计（按墙钟增量，仅旋转封面 + 播放中推进）。 */
    private var coverRotation = 0f
    private var lastCoverFrameAt = 0L
    /** 封面停靠进度目标驱动：target 0=大封面引导卡/1=角落停靠；from→target 在 DOCK_MS 内 easeOut 过渡。 */
    private var dockFrom = 0f
    private var dockTarget = 0f
    private var dockAnimAt = 0L            // 当前停靠/回退动画起点；0=已静止在 dockTarget
    private var dockCompletedAt = 0L       // 停靠到位的墙钟（文字/歌名据此淡入）；0=未停靠
    private var revertPendingSince = 0L    // 进度回到首句前、等待去抖回退的起点；0=无
    /** 角落小封面播放/暂停缩放：当前值=easeOut 插值 from→target。 */
    private var coverScaleFrom = 1f
    private var coverScaleTarget = 1f
    private var coverScaleStartedAt = 0L
    /** 停靠后文字区显示歌名的起点（墙钟）：自动模式下此后 NAME_SHOW_MS 内显示歌名再切时间；跑马灯起点。 */
    private var nameRevealAt = 0L
    /** 手动覆盖：-1=自动（歌名 NAME_SHOW_MS 后切时间）、0=强制时间、1=强制歌名。点击封面/时间区切换。 */
    private var manualNameOverride = -1
    /** 歌名↔时间交叉淡入淡出：当前因子 1=歌名 0=时间，from→target 在 NAME_TIME_SWITCH_MS 内过渡。 */
    private var dockNameFrom = 1f
    private var dockNameTarget = 1f
    private var dockNameAnimAt = 0L
    /** 封面命中区（视图坐标）：双击封面 → 放大成控制面板（onCoverTap）；单击 → 弹"双击打开控制页"提示。 */
    private val coverHitRect = RectF()
    /** 封面单击/双击判定：[coverTapAt]=上次单击墙钟（判双击）；[coverHintAt]=提示起始墙钟（0=未显示）。 */
    private var coverTapAt = 0L
    private var coverHintAt = 0L
    /** 角落封面当前是否已停靠 + 文字带命中区（封面左侧文字区，点击切换歌名/时间）。 */
    private var coverDocked = false
    private val textHitRect = RectF()

    /** 点击封面的回调（放大成收藏/切歌/暂停控制面板，由 RearLyricActivity 设置）。 */
    var onCoverTap: (() -> Unit)? = null

    /** 调整进度回调（点击某句歌词 → MediaControl.seekTo + 起播；拖动松手不 seek）。 */
    var onSeek: ((Long) -> Unit)? = null

    /**
     * 拖动浏览时上报「正中那句歌词」的时间，供 RearLyricActivity 在全屏 Compose 层把时间画到
     * 歌词左侧（可溢出到左侧摄像头空隙，FullLyricView 自身只占右 2/3、画不到那里）。
     */
    var onScrubChange: ((active: Boolean, timeMs: Long) -> Unit)? = null

    /**
     * 长按蓄力/解锁动画的锁视觉参数上报，供 RearLyricActivity 把进度环+小锁画在歌词左侧时间处
     * （同样在左 1/3 摄像头空隙，本 view 画不到）。visible=false 时隐藏。
     */
    var onLockVisual: ((visible: Boolean, ringProgress: Float, ringAlpha: Float, lockOpen: Float, lockAlpha: Float) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    // ---- 锁定 / 长按解锁 ----
    /** 歌词默认锁定；长按 2s 解锁后可点击跳转/拖动调整进度，空闲 [UNLOCK_IDLE_RELOCK_MS] 自动回锁。 */
    private var unlocked = false
    /** 长按蓄力起点（墙钟，0=未蓄力）；蓄力中左侧时间处画圆形进度+小锁。 */
    private var pressArmAt = 0L
    private var pressDownX = 0f
    private var pressDownY = 0f
    /** 小锁开/合动画起点（墙钟，0=无）：解锁=开锁动画，回锁=合锁动画。 */
    private var lockAnimAt = 0L
    private var lockAnimOpening = true
    /** 锁视觉上次是否可见（仅在 true→false 时补发一次隐藏，避免每帧回调）。 */
    private var lockReported = false
    private val relockRunnable = Runnable { doRelock() }
    private val unlockRunnable = Runnable { doUnlock() }
    /** 封面单击延时确认（在双击窗口内没有第二击才弹提示）。 */
    private val coverSingleTapRunnable = Runnable { triggerCoverHint() }

    // ---- 拖动调整进度（scrub，自由滚动 + 惯性）----
    /** 手动浏览中：渲染用 [scrubScroll] 取代直播滚动（手指抓取/惯性/松手停留期间都为 true）。 */
    private var scrubbing = false
    /** 手指当前是否按下。 */
    private var scrubGrabbed = false
    private var scrubScroll = 0f
    private var grabScroll = 0f
    private var lastTouchY = 0f
    private var movedScrub = false
    private var scrubCenterIndex = -1
    /** 惯性滑动：速度（px/s，scrubScroll 方向）与上一帧墙钟。 */
    private var flinging = false
    private var flingVel = 0f
    private var lastFlingAt = 0L
    private var velocityTracker: VelocityTracker? = null

    // ---- 本地 seek 覆盖（防直播进度未追上前回弹）----
    private var pendingSeekMs = -1L
    private var pendingSeekAt = 0L

    /**
     * 控制面板展开进度（0=正常停靠，1=面板模式）：面板模式下封面回到「首句前大封面」状态
     * ——复用 dock 动画从角落放大到中央（已是大封面则无动画），旋转角与「封面+歌名/歌手」排版
     * 全部沿用；歌词整体淡出让位给黑底；退出时封面缩回角落、歌词淡入。由 RearLyricActivity 驱动。
     */
    private var panelProgress = 0f
    private val panelMode get() = panelProgress > 0.01f

    fun setPanelProgress(p: Float) {
        // 仅记录进度并重绘：封面停靠进度由 coverDockProgress 直接按 panelProgress 同步拉向"大封面"，
        // 故封面回位/歌名淡出/角落时钟淡出全与面板开合（及底部按键）同步进行，不再滞后。
        panelProgress = p.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * SuperLyric 单句模式：封面**常驻大封面**（不停靠角落）+ 封面下方只排版**当前一句**歌词
     * （类似首句前样式，但下方是当前句而非圆点）。由 RearLyricActivity 据数据源设置。
     */
    private var singleLineMode = false

    fun setSingleLineMode(b: Boolean) {
        if (singleLineMode == b) return
        singleLineMode = b
        slLineKey = null
        invalidate()
    }

    // ---- SuperLyric 单句模式：当前句独立排版（下半区，独立字号）+ 随机升起进场 ----
    /** 当前句排版后的 token（独立字号、≤3 行、下半区居中）。 */
    private var slTokens: List<Token> = emptyList()
    /** 排版缓存键（文本+宽高+配色等变化才重排，重排即重启升起动画）。 */
    private var slLineKey: String? = null
    private var slFontSize = 0f
    private var slRowHeight = 0f
    /** 当前句升起动画起点（句变化时置 now）。 */
    private var slAnimAt = 0L
    /** 上次 bind 的墙钟时间（逐字扫过逐帧外推进度用）。 */
    private var bindWallAt = 0L

    /** 24 小时时间文字按分钟缓存。 */
    private var clockText = ""
    private var clockMinute = -1L
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var lines: List<RichLyricLine> = emptyList()
    private var lineAligns: List<LineAlign> = emptyList()
    private var models: List<LineModel> = emptyList()
    private var secEntries: List<SecondaryEntry?> = emptyList()
    private var titleLayout: StaticLayout? = null
    private var titleShiftPx = 0f
    private var baseTops = FloatArray(0)
    private var textSizePx = 0f
    private var lineGapPx = 0f
    private var secGapPx = 0f
    private var sinkPx = 0f
    private var dotSlotHeight = 0f
    private var layoutKey: LayoutKey? = null

    /** 提取封面渐变色的高亮 shader（参照词幕：≥2 色横向 LinearGradient）。 */
    private var hiShader: Shader? = null
    private var hiShaderKey: Pair<Int, LyricColors>? = null

    /** 当前句下标；-1 表示首句之前（圆点阶段），MIN_VALUE 表示未初始化。 */
    private var currentIndex = Int.MIN_VALUE
    private var scrollAnimating = false
    private var scrollFrom = 0f
    private var scrollStartedAt = 0L
    private var lastScroll = Float.NaN

    /** 副歌词渐显（当前句）与收起（上一句）状态。 */
    private var revealIndex = -1
    private var revealStartedAt = 0L
    private var collapseIndex = -1
    private var collapseStartedAt = 0L
    private var collapseFrom = 0f

    /** 抢唱重叠：迟唱的下一句下标（在原位正常演唱、取消模糊；前句唱完它再滚到中间）；-1 表示无。 */
    private var overlapIndex = -1
    /** 当前句放大/缩回动画：起始墙钟、上一个当前句（用于缩回 1.0）。 */
    private var zoomStartedAt = 0L
    private var prevCurrentIndex = Int.MIN_VALUE
    /** 当前句自带副句是否已"按时机武装"（每句切换后重置，避免每帧重复触发）。 */
    private var revealArmed = false

    /** 换歌整页上滑过渡：旧画面快照 + 起始墙钟。 */
    private var songSwitchBitmap: Bitmap? = null
    private var songSwitchStartedAt = 0L
    /** 切歌后在此墙钟前强制按"首句前"渲染（新歌从头、显示大封面），滤掉 provider 补发的旧歌 stale 进度。 */
    private var songSwitchHoldUntil = 0L
    private val switchPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 换歌封面过渡（独立于整页底板）：旧封面飞出 + 新封面（加载后）飞入，封面图片与封面槽位解耦。 */
    private var coverExitBitmap: Bitmap? = null
    private var coverExitAt = 0L
    private var coverExitFromCx = 0f
    private var coverExitFromCy = 0f
    private var coverExitFromSize = 0f
    private var coverExitDocked = false
    private var coverEnterAt = 0L          // 新封面飞入起点；0=未飞入
    private var coverEnterPending = false  // 已切歌、等新封面加载后飞入
    /** 最近一帧封面槽位几何（飞入目标 / 飞出起点用）。 */
    private var lastCoverCx = 0f
    private var lastCoverCy = 0f
    private var lastCoverSize = 0f
    private var lastCoverDocked = false
    /** 正在为整页底板快照渲染：此时跳过所有封面绘制，封面交给飞入/飞出层。 */
    private var drawingSnapshot = false

    fun bind(
        song: Song?,
        positionMs: Long,
        config: RearConfig,
        colors: LyricColors,
        playerPackage: String?,
        coverBitmap: Bitmap?,
        playing: Boolean,
    ) {
        val songChanged = this.song !== song
        // 区分「真正换歌」与「同一首歌随后补下发歌词」：provider 常先下发无歌词的元数据、
        // 再下发带歌词的同一首（两个不同对象）。若都按对象引用当作换歌，会在元数据到达时
        // 上滑一次、歌词到达时再顶一次（出现两次切换动画）。仅「真正换歌」才做整页上滑过渡
        // 与状态重置；同一首补歌词只重建排版让歌词就地显示（snap 就位，不再触发上滑）。
        val isNewSong = !isSameLogicalSong(this.song, song)
        if (isNewSong) {
            // 换歌：退出拖动浏览、回锁、撤销本地 seek 覆盖（新歌重新从锁定态开始）。
            scrubbing = false
            scrubGrabbed = false
            movedScrub = false
            flinging = false
            cancelUnlockHold()
            if (unlocked) {
                unlocked = false
                handler.removeCallbacks(relockRunnable)
            }
            pendingSeekMs = -1L
            onScrubChange?.invoke(false, 0L)
        }
        // 切歌：接下来 SONG_SWITCH_HOLD_MS 内强制新歌按"首句前"渲染（默认从头、显示大封面）。
        if (isNewSong && this.song != null) {
            songSwitchHoldUntil = SystemClock.elapsedRealtime() + SONG_SWITCH_HOLD_MS
        }
        // 换歌封面过渡：把当前(旧)封面拎出来飞出，新封面等加载后再飞入；封面图片与封面槽位解耦。
        // 必须在快照之前置位，让底板快照（drawingSnapshot）跳过封面，避免封面被烤进底板与飞出层重影。
        if (isNewSong && this.song != null && config.cover != CoverPosition.NONE && width > 0 && height > 0) {
            val cur = this.coverBitmap
            if (cur != null && lastCoverSize > 0f) {
                coverExitBitmap = cur
                coverExitAt = SystemClock.elapsedRealtime()
                coverExitFromCx = lastCoverCx
                coverExitFromCy = lastCoverCy
                coverExitFromSize = lastCoverSize
                coverExitDocked = lastCoverDocked
            }
            coverEnterPending = true
            coverEnterAt = 0L
        }
        // 换歌：先快照当前画面，onDraw 让它上滑淡出、新歌内容从下方顶上来。
        if (isNewSong && this.song != null && song != null && width > 0 && height > 0) {
            captureSongSwitchSnapshot()
        }
        // 封面对象变化（换歌/重解码）时旋转角归零，与单句模式一致。
        if (this.coverBitmap !== coverBitmap) {
            this.coverBitmap = coverBitmap
            coverRotation = 0f
            lastCoverFrameAt = 0L
            // 新封面加载到位（切歌等待期间）：开始飞入。
            if (coverEnterPending && coverBitmap != null) {
                coverEnterAt = SystemClock.elapsedRealtime()
                coverEnterPending = false
            }
        }
        // 播放/暂停切换：角落小封面 1↔1.1 平滑缩放。
        if (playing != this.playing) {
            val now = SystemClock.elapsedRealtime()
            coverScaleFrom = currentCoverScale(now)
            coverScaleTarget = if (playing) DOCK_COVER_PLAY_SCALE else 1f
            coverScaleStartedAt = now
        }
        this.playing = playing
        this.song = song
        // 切歌后 hold 窗口内按进度 0（首句前）渲染，窗口过后再采用新歌真实进度，
        // 滤掉 provider 在 onSongChanged 之后补发的上一首 stale 进度导致的"新歌从旧进度开始"。
        val now0 = SystemClock.elapsedRealtime()
        val holdPos = if (now0 < songSwitchHoldUntil) 0L else positionMs
        // 本地 seek 覆盖：点击/拖动 seek 后，直播进度还没追上目标前先按目标渲染（避免回弹）；
        // 直播追上（误差 ≤ SEEK_SETTLE_MS）或超时即撤销覆盖、回到直播进度。
        this.positionMs = if (pendingSeekMs >= 0L) {
            if (abs(holdPos - pendingSeekMs) <= SEEK_SETTLE_MS || now0 - pendingSeekAt > SEEK_HOLD_MAX_MS) {
                pendingSeekMs = -1L
                holdPos
            } else {
                pendingSeekMs
            }
        } else {
            holdPos
        }
        bindWallAt = now0
        this.config = config
        this.colors = colors
        this.playerPackage = playerPackage
        val key = LayoutKey(
            songId = System.identityHashCode(song),
            width = width,
            padLeft = paddingLeft,
            padRight = paddingRight,
            bold = config.bold,
            italic = config.italic,
            fontWeight = config.fontWeight,
            showSecondary = config.showSecondary,
            showTranslation = config.showTranslation,
            showRoma = config.showRoma,
            relativeHighlight = config.relativeHighlight,
            simulateWordTiming = config.simulateWordTiming,
            lyricTextSize = config.lyricTextSize,
            playerPackage = playerPackage,
        )
        if (key != layoutKey) {
            layoutKey = key
            rebuildLayouts()
        }
        refreshHighlightShader()
        // 拖动浏览期间滚动由手指控制，不让直播进度推动 currentIndex/启动滚动动画。
        if (!scrubbing) updateScroll(snap = songChanged)
        invalidate()
    }

    /**
     * 是否是「另一首歌」（用于区分真正换歌与同一首歌补下发歌词）。
     * 同一首判定：id 都有且相等，或 歌名+歌手 都有且相等（任一成立即同一首）。
     * 这样既能容忍 provider 仅在某次下发里带 id、也能容忍补歌词时 id 变化——只要
     * 歌名/歌手稳定就不会被误判成换歌而触发第二次整页上滑动画。
     */
    private fun isSameLogicalSong(a: Song?, b: Song?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        val aId = a.id?.takeIf { it.isNotBlank() }
        val bId = b.id?.takeIf { it.isNotBlank() }
        if (aId != null && bId != null && aId == bId) return true
        val aName = a.name?.takeIf { it.isNotBlank() }
        val bName = b.name?.takeIf { it.isNotBlank() }
        return aName != null && bName != null && aName == bName && a.artist == b.artist
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(unlockRunnable)
        handler.removeCallbacks(relockRunnable)
        handler.removeCallbacks(coverSingleTapRunnable)
        recycleVelocity()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        layoutKey = layoutKey?.copy(width = w)
        rebuildLayouts()
        refreshHighlightShader()
        updateScroll(snap = true)
        invalidate()
    }

    /** 换歌前把当前画面快照下来（用当前/旧状态渲染），供 onDraw 做上滑过渡。 */
    private fun captureSongSwitchSnapshot() {
        songSwitchBitmap?.recycle()
        songSwitchBitmap = null
        val bmp = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        // 底板快照不含封面（封面交给飞出/飞入层），避免封面被烤进底板再叠一份飞出造成重影。
        drawingSnapshot = true
        draw(Canvas(bmp))
        drawingSnapshot = false
        songSwitchBitmap = bmp
        songSwitchStartedAt = SystemClock.elapsedRealtime()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        val snapshot = songSwitchBitmap
        if (snapshot != null && !snapshot.isRecycled && now - songSwitchStartedAt < SONG_SWITCH_MS) {
            val p = 1f - (1f - ((now - songSwitchStartedAt) / SONG_SWITCH_MS).coerceIn(0f, 1f)).pow(3)
            val shift = height * SONG_SWITCH_SHIFT_FRAC
            // 旧歌词上滑淡出。
            switchPaint.alpha = ((1f - p) * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(snapshot, 0f, -p * shift, switchPaint)
            // 新歌词从下方顶上来。
            canvas.save()
            canvas.translate(0f, (1f - p) * shift)
            renderLyrics(canvas, now)
            canvas.restore()
            // 封面飞出/飞入独立于底板上滑（绝对坐标，不跟随底板位移）。
            drawCoverTransition(canvas, now)
            postInvalidateOnAnimation()
            return
        }
        if (snapshot != null) {
            snapshot.recycle()
            songSwitchBitmap = null
        }
        renderLyrics(canvas, now)
        drawCoverTransition(canvas, now)
    }

    private fun renderLyrics(canvas: Canvas, now: Long) {
        // 每帧重置命中区，由封面绘制按当前状态重新设置（无封面/无歌词时为空，不响应点击）。
        coverHitRect.setEmpty()
        textHitRect.setEmpty()
        if (singleLineMode) {
            // SuperLyric：上半区大封面 + 下半区当前句（独立字号、≤3 行、随机升起）。
            renderSuperLyric(canvas, now)
            return
        }
        if (models.isEmpty()) {
            // 无歌词：全量模式退化为"封面 + 歌名 + 歌手"居中展示（同歌曲信息模式）。
            drawNoLyricCard(canvas, now)
            return
        }
        // 惯性滑动推进（松手后继续滑，丝滑停下）。
        if (scrubbing && flinging) advanceFling(now)
        val scroll: Float
        if (scrubbing) {
            // 拖动浏览：滚动位置由手指/惯性控制，不走句切动画。
            scrollAnimating = false
            scroll = scrubScroll
        } else {
            val target = targetScrollFor(currentIndex, now)
            if (scrollAnimating && !lastScroll.isNaN()) {
                val t = ((now - scrollStartedAt) / SCROLL_DURATION_MS).coerceIn(0f, 1f)
                if (t >= 1f) {
                    scrollAnimating = false
                    scroll = target
                } else {
                    scroll = scrollFrom + (target - scrollFrom) * (1f - (1f - t).pow(3))
                }
            } else {
                scrollAnimating = false
                scroll = target
            }
        }
        lastScroll = scroll

        // 当前句锚点：词幕源居中（height/2）；SuperLyric 单句模式把当前句画在大封面下方。
        val viewCenter = if (singleLineMode) {
            val contentTop = paddingTop.toFloat()
            val contentHeight = (height - paddingTop - paddingBottom).toFloat()
            contentTop + contentHeight * INTRO_TOP_FRAC + introCoverSizePx() + textSizePx * 1.6f
        } else {
            height / 2f
        }
        val fadeRange = FADE_SLOTS * (textSizePx * 1.2f + lineGapPx)

        canvas.save()
        // 仅横向裁到内容区（避开右侧挖孔/边缘），纵向放开到整屏，歌词可自然滑出上下屏幕外。
        canvas.clipRect(
            paddingLeft.toFloat(),
            0f,
            (width - paddingRight).toFloat(),
            height.toFloat(),
        )
        canvas.translate(paddingLeft.toFloat(), viewCenter - scroll)

        // 控制面板展开：全量滚动歌词不消失，淡到背景亮度（最早方案：歌词放背景），封面/进度条/按键叠其上。
        val lyricFade = 1f - 0.62f * panelProgress
        if (lyricFade > 0.01f) {
            // 绘制窗口以「当前可见滚动位置」为中心，并覆盖 currentIndex。长距离 seek/拖动时滚动动画
            // 途经的中间句若不在 currentIndex±半径内会整屏空白——这里按可见位置取窗口即可修复。
            val visualCenter = nearestIndexTo(scroll, now)
            val anchor = if (currentIndex in models.indices) currentIndex else visualCenter
            val first = (min(visualCenter, anchor) - DRAW_RADIUS).coerceAtLeast(0)
            val last = (max(visualCenter, anchor) + DRAW_RADIUS).coerceAtMost(models.lastIndex)
            for (i in first..last) {
                val top = topOf(i, now)
                val blockHeight = blockHeightOf(i, now)
                val norm = abs(top + blockHeight / 2f - scroll) / fadeRange
                // 当前句与抢唱副句都在演唱：全强度、不渐隐模糊、始终绘制（超长句句内滚动时块心可能远离 scroll）。
                val isActive = i == currentIndex || i == overlapIndex
                if (!isActive && norm >= 1f) continue
                val alphaF = (if (isActive) 1f else (1f - norm).pow(FADE_EXPONENT)) * lyricFade
                val blur = if (isActive) null else blurFor(norm)
                drawLineBlock(canvas, i, top, blockHeight, alphaF, blur, revealFactorAt(i, now), norm, lineScale(i, now))
            }
        }
        paint.maskFilter = null
        secPaint.maskFilter = null

        // 有封面时首句前用"封面+歌名+歌手"引导卡（首句出来后缩到角落带时间）；
        // 无封面/不显示封面时回退到旧的"歌名-歌手 + 圆点"文字引导。
        val coverWidgetEnabled = coverBitmap != null && config.cover != CoverPosition.NONE
        if (!coverWidgetEnabled) {
            drawIntro(canvas)
        }
        canvas.restore()

        if (coverWidgetEnabled) {
            drawCoverWidget(canvas, now)
        }

        // 长按蓄力/解锁动画：上报锁视觉参数给 Compose（画在左侧歌词时间处，本 view 画不到左 1/3）。
        reportLockVisual(now)

        val revealAnimating = (revealIndex >= 0 && now - revealStartedAt < REVEAL_MS) ||
            (collapseIndex >= 0 && now - collapseStartedAt < REVEAL_MS)
        val zoomAnimating = now - zoomStartedAt < ZOOM_MS.toLong()
        val lockBusy = pressArmAt != 0L ||
            (lockAnimAt != 0L && now - lockAnimAt < LOCK_ANIM_MS.toLong()) ||
            flinging
        val coverAnimating = coverWidgetEnabled && (
            // 停靠/回退动画、停靠后文字淡入、等待去抖回退期间持续重绘。
            (dockAnimAt != 0L && now - dockAnimAt < DOCK_MS.toLong()) ||
                (dockCompletedAt != 0L && now - dockCompletedAt < CLOCK_FADE_MS.toLong()) ||
                revertPendingSince != 0L ||
                (config.coverShape == CoverShape.CIRCLE_ROTATE && playing) ||
                (coverScaleStartedAt != 0L && now - coverScaleStartedAt < DOCK_SCALE_MS.toLong()) ||
                // 歌名↔时间交叉过渡期间持续重绘。
                (dockNameAnimAt != 0L && now - dockNameAnimAt < NAME_TIME_SWITCH_MS.toLong()) ||
                // 歌名显示中（跑马灯/自动切时间计时）持续重绘。
                (coverDocked && dockNameTarget >= 0.5f) ||
                // 单击封面的"双击打开控制页"提示淡入淡出期间持续重绘。
                (coverHintAt != 0L && now - coverHintAt < COVER_HINT_MS.toLong())
        )
        if (scrollAnimating || revealAnimating || zoomAnimating || overlapIndex >= 0 || coverAnimating || lockBusy ||
            coverTransitionActive(now)
        ) {
            postInvalidateOnAnimation()
        }
    }

    // ---- SuperLyric 单句渲染 ----

    /**
     * SuperLyric 单句模式渲染：上半区沿用「大封面 + 歌名/歌手」（[drawCoverWidget]，封面常驻不停靠），
     * 下半区把**当前一句**以独立字号（一行 7 汉字、≤3 行、下半区垂直居中）绘制；句变化时逐字/逐词
     * 「随机升起」进场；右上角画时钟（仅时间，不点击切歌名）。
     */
    private fun renderSuperLyric(canvas: Canvas, now: Long) {
        // SL 模式不走滚动；归零 lastScroll 让封面提示等依赖项有确定基准。
        lastScroll = 0f
        val line = lines.firstOrNull()
        val text = line?.text?.takeIf { it.isNotBlank() }
        val key = if (text == null) {
            null
        } else {
            "$text|${width - paddingLeft - paddingRight}|$height|${config.textColorMode}|" +
                "${config.relativeHighlight}|${config.lyricAnimation}|${config.fontWeight}|${config.italic}"
        }
        if (key != slLineKey) {
            slLineKey = key
            if (line != null && text != null) {
                buildSuperLyricLayout(line)
                slAnimAt = now
            } else {
                slTokens = emptyList()
            }
        }

        // 下半区当前句（逐字扫过 + 随机升起）。控制面板展开时淡到背景。
        val lyricFade = 1f - 0.62f * panelProgress
        if (lyricFade > 0.01f && slTokens.isNotEmpty()) {
            drawSuperLyricTokens(canvas, now, lyricFade)
        }

        // 上半区：大封面 + 歌名/歌手（无封面则不画引导卡，仅留歌词）。
        val coverWidgetEnabled = coverBitmap != null && config.cover != CoverPosition.NONE
        if (coverWidgetEnabled) {
            drawCoverWidget(canvas, now)
        }

        // 右上角时钟（仅时间，位置与词幕模式停靠时的时间完全一致）。
        drawSingleLineClock(canvas, now)

        reportLockVisual(now)

        val animOn = config.lyricAnimation == LyricAnimation.RANDOM_RISE
        val riseActive = animOn && slAnimAt != 0L &&
            now - slAnimAt < SL_RISE_STAGGER_MS + SL_RISE_DUR_MS.toLong() + 60L
        val lockBusy = pressArmAt != 0L || (lockAnimAt != 0L && now - lockAnimAt < LOCK_ANIM_MS.toLong())
        val coverAnimating = coverWidgetEnabled && (
            (config.coverShape == CoverShape.CIRCLE_ROTATE && playing) ||
                (coverScaleStartedAt != 0L && now - coverScaleStartedAt < DOCK_SCALE_MS.toLong()) ||
                (coverHintAt != 0L && now - coverHintAt < COVER_HINT_MS.toLong()) ||
                coverTransitionActive(now)
            )
        // 播放中：逐字扫过靠逐帧外推推进，需要持续重绘。
        if (riseActive || playing || lockBusy || coverAnimating) {
            postInvalidateOnAnimation()
        }
    }

    /** 当前句排版：复用逐词时间，自算独立字号 + ≤3 行换行 + 下半区垂直居中 + 升起随机时序。 */
    private fun buildSuperLyricLayout(line: RichLyricLine) {
        val contentLeft = paddingLeft.toFloat()
        val contentWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val contentTop = paddingTop.toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        slPaint.typeface = buildTypeface(config)

        // 复用逐词排版只为拿「文本 + 逐字 begin/end」；此处宽度给超大值避免在 buildLineModel 内换行。
        slPaint.textSize = 100f
        val baseTokens = buildLineModel(
            textPaint = slPaint,
            lineText = line.text.orEmpty(),
            lineBegin = line.begin,
            lineEnd = line.end,
            words = line.words,
            contentWidth = contentWidth * 8f,
            // SuperLyric「随机升起」要求中文逐字、英文逐词，故强制按单字拆 CJK（不随逐字高亮开关）。
            splitCjkWords = true,
            align = LineAlign.LEFT,
            latinExtraPx = 0f,
        ).tokens
        if (baseTokens.isEmpty()) {
            slTokens = emptyList()
            return
        }

        // 下半区与竖向可用高度（留上下边距，避免末行贴边/被截断）。
        val bottomTop = contentTop + contentHeight * 0.5f
        val bottomHeight = contentHeight * 0.5f
        val availH = bottomHeight * SL_BOTTOM_FILL

        // 字号上限：一行放 7 个汉字占满内容宽（字少时也按此上限，避免显得偏小）。
        slPaint.textSize = 100f
        val unit = (slPaint.measureText("汉") * SL_CHARS_PER_LINE).coerceAtLeast(1f)
        var size = 100f * contentWidth / unit

        // 同时满足：① ≤3 行；② 总高 ≤ 下半区可用高度（否则第三行被竖向截断）；
        // ③ 最宽单 token（如超长英文单词，不拆词换行）≤ 内容宽（否则该词横向超出被截断）。
        // 任一不满足就继续缩字号（无下限）。
        var rows = wrapSuperLyricRows(baseTokens, contentWidth, size)
        var rowHeight = slRowHeightAt(size)
        while ((rows.size > SL_MAX_ROWS || rows.size * rowHeight > availH ||
                slWidestTokenWidth(baseTokens, size) > contentWidth) && size > 4f
        ) {
            size *= 0.94f
            rows = wrapSuperLyricRows(baseTokens, contentWidth, size)
            rowHeight = slRowHeightAt(size)
        }
        slFontSize = size
        slPaint.textSize = size
        slRowHeight = rowHeight
        val ascent = -slPaint.ascent()

        // 下半区垂直居中（1/2/3 行都在下半区居中）。
        val totalH = rows.size * rowHeight
        val blockTop = bottomTop + ((bottomHeight - totalH) / 2f).coerceAtLeast(0f)

        rows.forEachIndexed { r, rowToks ->
            slPaint.textSize = size
            val rowW = rowToks.sumOf { slPaint.measureText(it.text).toDouble() }.toFloat()
            var x = contentLeft + ((contentWidth - rowW) / 2f).coerceAtLeast(0f)
            val top = blockTop + r * rowHeight
            for (tok in rowToks) {
                tok.size = size
                tok.width = slPaint.measureText(tok.text)
                tok.x = x
                tok.rowTop = top
                tok.rowBottom = top + rowHeight
                tok.baseline = top + ascent
                tok.riseDelay = (Math.random() * SL_RISE_STAGGER_MS).toLong()
                x += tok.width
            }
        }
        slTokens = rows.flatten()
    }

    /** 给定字号下最宽单 token 的宽度（用于保证超长单词单行也不被横向截断）。 */
    private fun slWidestTokenWidth(tokens: List<Token>, size: Float): Float {
        slPaint.textSize = size
        var w = 0f
        for (t in tokens) w = max(w, slPaint.measureText(t.text))
        return w
    }

    /** 给定字号下的单行行高：按字形盒高（descent-ascent）+ 少量呼吸间距，去掉字体多余行距。 */
    private fun slRowHeightAt(size: Float): Float {
        slPaint.textSize = size
        val fm = slPaint.fontMetrics
        return (fm.descent - fm.ascent) * SL_ROW_SPACING
    }

    /** 贪心换行（按当前 [size] 测宽），单 token 超宽则独占一行。 */
    private fun wrapSuperLyricRows(tokens: List<Token>, width: Float, size: Float): List<List<Token>> {
        slPaint.textSize = size
        val rows = ArrayList<MutableList<Token>>()
        var row = ArrayList<Token>()
        var w = 0f
        for (t in tokens) {
            val tw = slPaint.measureText(t.text)
            if (row.isNotEmpty() && w + tw > width) {
                rows += row
                row = ArrayList()
                w = 0f
            }
            row += t
            w += tw
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    /** 下半区当前句绘制：逐字扫过（沿用渐变/clip 进度逻辑）+ 每 token 随机升起进场。 */
    private fun drawSuperLyricTokens(canvas: Canvas, now: Long, fade: Float) {
        // SuperLyric 进度来自系统会话（~500ms 一拍），逐帧按经过时间外推让逐字扫过平滑。
        val effPos = if (playing) positionMs + (now - bindWallAt).coerceAtLeast(0L) else positionMs
        val animOn = config.lyricAnimation == LyricAnimation.RANDOM_RISE
        val baseA = 255f * fade
        val hiA = 255f * fade
        val riseDist = slRowHeight * SL_RISE_DIST_FRAC
        val baseSize = slPaint.textSize
        for (tok in slTokens) {
            // 进场升起：每 token 随机延迟、easeOutCubic（非线性）从下方升起 + 渐显。
            val ease = if (!animOn) {
                1f
            } else {
                val t = ((now - slAnimAt - tok.riseDelay).toFloat() / SL_RISE_DUR_MS).coerceIn(0f, 1f)
                easeOutCubic(t)
            }
            val dy = (1f - ease) * riseDist
            val a = ease
            slPaint.textSize = tok.size
            val sung = effPos >= tok.end || (!config.relativeProgress && effPos > tok.begin)
            when {
                sung -> {
                    applyHighlight(slPaint, hiA * a)
                    canvas.drawText(tok.text, tok.x, tok.baseline + dy, slPaint)
                }
                effPos <= tok.begin -> {
                    slPaint.shader = null
                    slPaint.color = Color.WHITE
                    slPaint.alpha = (baseA * a).roundToInt().coerceIn(0, 255)
                    canvas.drawText(tok.text, tok.x, tok.baseline + dy, slPaint)
                }
                else -> {
                    slPaint.shader = null
                    slPaint.color = Color.WHITE
                    slPaint.alpha = (baseA * a).roundToInt().coerceIn(0, 255)
                    canvas.drawText(tok.text, tok.x, tok.baseline + dy, slPaint)
                    val dur = (tok.end - tok.begin).coerceAtLeast(1L)
                    val f = ((effPos - tok.begin).toFloat() / dur).coerceIn(0f, 1f)
                    val front = tok.x + tok.width * f
                    if (config.gradientProgress) {
                        val soft = (tok.size * GRADIENT_SOFT_EM).coerceAtMost(tok.width)
                        canvas.saveLayer(tok.x, tok.rowTop + dy, tok.x + tok.width, tok.rowBottom + dy, null)
                        applyHighlight(slPaint, hiA * a)
                        canvas.drawText(tok.text, tok.x, tok.baseline + dy, slPaint)
                        maskPaint.shader = LinearGradient(
                            front - soft, 0f, front, 0f,
                            HIGHLIGHT_MASK_COLORS, null, Shader.TileMode.CLAMP,
                        )
                        maskPaint.xfermode = dstInXfermode
                        canvas.drawRect(tok.x, tok.rowTop + dy, tok.x + tok.width, tok.rowBottom + dy, maskPaint)
                        maskPaint.xfermode = null
                        maskPaint.shader = null
                        canvas.restore()
                    } else {
                        canvas.save()
                        canvas.clipRect(tok.x, tok.rowTop + dy, front, tok.rowBottom + dy)
                        applyHighlight(slPaint, hiA * a)
                        canvas.drawText(tok.text, tok.x, tok.baseline + dy, slPaint)
                        canvas.restore()
                    }
                }
            }
        }
        slPaint.shader = null
        slPaint.textSize = baseSize
    }

    /**
     * 顶部时钟（SuperLyric 单句模式，仅时间）：垂直沿用词幕停靠时间的高度（停靠封面中心 Y），
     * 水平**居左**贴内容左缘（不再像词幕那样靠右——SL 无角落封面，靠右会留空且显得偏右）。
     */
    private fun drawSingleLineClock(canvas: Canvas, now: Long) {
        val margin = dp(DOCK_MARGIN_DP)
        val dockCover = dockCoverSizePx() * currentCoverScale(now)
        val centerY = margin + dockCover / 2f
        clockPaint.shader = null
        clockPaint.color = Color.WHITE
        clockPaint.textSize = dockCoverSizePx() * CLOCK_TEXT_FRAC
        clockPaint.alpha = 235
        val fm = clockPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val text = currentClockText()
        canvas.drawText(text, paddingLeft.toFloat(), baseline, clockPaint)
    }

    // ---- 排版 ----

    private fun rebuildLayouts() {
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val typeface = buildTypeface(config)
        paint.typeface = typeface
        secPaint.typeface = typeface
        titlePaint.typeface = typeface
        paint.textSize = 100f
        val sample = paint.measureText("汉") * CHARS_PER_LINE
        // 排版预留当前句演唱放大量（CURRENT_ZOOM）：按缩小后的宽度适配九字，放大时不会超出右边被裁掉。
        val wrapWidth = contentWidth / (1f + CURRENT_ZOOM)
        val autoSize = if (sample > 0f) 100f * wrapWidth / sample else wrapWidth / CHARS_PER_LINE.toFloat()
        // 回报自动字号(px)，配置页据此把"歌词文字大小"默认显示为当前实际值（而非 0）。
        LyricRenderMetrics.autoTextSizePx.value = autoSize.roundToInt()
        // 歌词字号：用户设了绝对 px 则用之，否则用自动适配值。
        textSizePx = if (config.lyricTextSize > 0) config.lyricTextSize.toFloat() else autoSize
        paint.textSize = textSizePx
        secPaint.textSize = textSizePx * SEC_EM
        titlePaint.textSize = textSizePx * TITLE_EM
        lineGapPx = textSizePx * LINE_GAP_EM
        secGapPx = textSizePx * SEC_GAP_EM
        sinkPx = textSizePx * SINK_EM
        dotSlotHeight = textSizePx * 1.2f

        lines = song?.lyrics.orEmpty().filter { !it.text.isNullOrBlank() }.sortedBy { it.begin }
        // Apple Music（或任一句带居右标记的来源）：直接采用逐句居左/居右；其它来源居中。
        val honorPerLine = playerPackage == APPLE_MUSIC_PKG || lines.any { it.isAlignedRight }
        lineAligns = lines.map { line ->
            if (honorPerLine) {
                if (line.isAlignedRight) LineAlign.RIGHT else LineAlign.LEFT
            } else {
                LineAlign.CENTER
            }
        }
        models = lines.mapIndexed { i, line ->
            buildLineModel(
                textPaint = paint,
                lineText = line.text.orEmpty(),
                lineBegin = line.begin,
                lineEnd = line.end,
                words = line.words,
                contentWidth = contentWidth.toFloat(),
                splitCjkWords = config.relativeHighlight,
                align = lineAligns[i],
                latinExtraPx = LATIN_EXTRA_PX,
            )
        }
        secEntries = lines.mapIndexed { i, line -> buildSecondaryEntry(line, contentWidth, lineAligns[i]) }
        buildTitle(contentWidth)
        buildIntroCard(contentWidth)

        baseTops = FloatArray(models.size)
        var y = 0f
        models.forEachIndexed { i, model ->
            baseTops[i] = y
            y += model.mainHeight + lineGapPx
        }
        currentIndex = Int.MIN_VALUE
        revealIndex = -1
        collapseIndex = -1
        scrollAnimating = false
        lastScroll = Float.NaN
        dockFrom = 0f
        dockTarget = 0f
        dockAnimAt = 0L
        dockCompletedAt = 0L
        revertPendingSince = 0L
        nameRevealAt = 0L
        manualNameOverride = -1
        dockNameFrom = 1f
        dockNameTarget = 1f
        dockNameAnimAt = 0L
        coverDocked = false
        coverTapAt = 0L
        coverHintAt = 0L
    }

    /** 引导卡歌名单次排版（无省略、不限行数，用于自适应缩字号判断真实行数）。 */
    private fun buildIntroName(text: String, width: Int, size: Float): StaticLayout {
        introNamePaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, introNamePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .build()
    }

    /** 引导卡（首句前）：封面旁的歌名/歌手布局。字号独立于歌词字号，按内容宽适度取值。 */
    private fun buildIntroCard(contentWidth: Int) {
        introNameLayout = null
        introArtistLayout = null
        val name = song?.name?.takeIf { it.isNotBlank() } ?: "未知歌曲"
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: "未知歌手"
        val typeface = buildTypeface(config)
        introNamePaint.typeface = typeface
        introArtistPaint.typeface = typeface
        introArtistPaint.textSize = textSizePx * INTRO_ARTIST_EM
        val coverSize = introCoverSizePx()
        val textWidth = (contentWidth - coverSize - textSizePx * INTRO_GAP_EM)
            .toInt().coerceAtLeast(1)
        // 歌名：字号上限=textSizePx*INTRO_NAME_EM，无下限；从上限往下缩，直到整段歌名能在 ≤3 行内完整显示（不省略）。
        val maxNameSize = textSizePx * INTRO_NAME_EM
        val nameStep = max(1f, maxNameSize * 0.04f)
        var nameSize = maxNameSize
        var nameLayout = buildIntroName(name, textWidth, nameSize)
        while (nameLayout.lineCount > 3 && nameSize > 6f) {
            nameSize = (nameSize - nameStep).coerceAtLeast(6f)
            nameLayout = buildIntroName(name, textWidth, nameSize)
        }
        introNameLayout = nameLayout
        introArtistLayout = StaticLayout.Builder.obtain(artist, 0, artist.length, introArtistPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(1)
            .build()
    }

    /**
     * 逐词排版：有逐字时间用词表（[splitCjkWords] 时词内汉字再拆成单字，时间按宽度均分；
     * 其它语言保持按词），无时间则切分后按宽度均分整句时间。
     */
    private fun buildLineModel(
        textPaint: TextPaint,
        lineText: String,
        lineBegin: Long,
        lineEnd: Long,
        words: List<LyricWord>?,
        contentWidth: Float,
        splitCjkWords: Boolean,
        align: LineAlign,
        latinExtraPx: Float,
    ): LineModel {
        val cjkSize = textPaint.textSize
        val latinSize = cjkSize + latinExtraPx
        // 行高/基线按较大字号（英文略大），保证大字不被裁；各 token 按自身字号绘制、共用同一基线（底对齐）。
        textPaint.textSize = max(cjkSize, latinSize)
        val rowHeight = textPaint.fontSpacing * LINE_SPACING_MULT
        val ascent = -textPaint.ascent()
        textPaint.textSize = cjkSize

        val wordList = words.orEmpty()
        val timed = wordList.isNotEmpty()
        val pieces: List<Token> = if (timed) {
            wordList.flatMap { w -> wordTokens(textPaint, w.text.orEmpty(), w.begin, w.end, splitCjkWords) }
        } else {
            splitPlain(lineText).map { Token(text = it) }
        }
        if (pieces.isEmpty()) {
            textPaint.textSize = cjkSize
            return LineModel(emptyList(), rowHeight, 1)
        }

        // 中文用中文字号、英文/其它用英文字号；按各自字号测宽。
        pieces.forEach { tok ->
            tok.size = if (tok.text.any { isCjk(it) }) cjkSize else latinSize
            textPaint.textSize = tok.size
            tok.width = textPaint.measureText(tok.text)
        }
        textPaint.textSize = cjkSize

        // 换行按预留了当前句放大量的宽度断行（居中/对齐仍用完整 contentWidth），
        // 这样当前句演唱放大 CURRENT_ZOOM 后仍落在内容区内、不被右侧裁切。
        val wrapWidth = contentWidth / (1f + CURRENT_ZOOM)
        val rows = ArrayList<MutableList<Token>>()
        var row = mutableListOf<Token>()
        var rowWidth = 0f
        for (tok in pieces) {
            if (row.isNotEmpty() && rowWidth + tok.width > wrapWidth) {
                rows += row
                row = mutableListOf()
                rowWidth = 0f
            }
            row += tok
            rowWidth += tok.width
        }
        if (row.isNotEmpty()) rows += row

        rows.forEachIndexed { r, rowTokens ->
            val rawWidth = rowTokens.sumOf { it.width.toDouble() }.toFloat()
            val lastTok = rowTokens.last()
            textPaint.textSize = lastTok.size
            val effWidth = rawWidth - (lastTok.width - textPaint.measureText(lastTok.text.trimEnd()))
            var x = when (align) {
                LineAlign.LEFT -> 0f
                LineAlign.CENTER -> ((contentWidth - effWidth) / 2f).coerceAtLeast(0f)
                LineAlign.RIGHT -> (contentWidth - effWidth).coerceAtLeast(0f)
            }
            val top = r * rowHeight
            for (tok in rowTokens) {
                tok.x = x
                tok.rowTop = top
                tok.rowBottom = top + rowHeight
                tok.baseline = top + ascent
                x += tok.width
            }
        }
        textPaint.textSize = cjkSize
        val tokens = rows.flatten()
        if (!timed) {
            if (config.simulateWordTiming) {
                // 模拟逐字：按字宽把整句时长摊给各 token，假装逐字扫过。
                val total = tokens.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
                val dur = (lineEnd - lineBegin).coerceAtLeast(1L)
                var cum = 0f
                for (tok in tokens) {
                    tok.begin = lineBegin + (dur * (cum / total)).toLong()
                    cum += tok.width
                    tok.end = lineBegin + (dur * (cum / total)).toLong()
                }
            } else {
                // 关闭模拟逐字：整句到点一起点亮（begin=end=lineBegin），整句整句切、不假装逐字。
                for (tok in tokens) {
                    tok.begin = lineBegin
                    tok.end = lineBegin
                }
            }
        }
        return LineModel(tokens, rows.size * rowHeight, rows.size)
    }

    /** 词内拆分：汉字逐字（下沉/点亮按单字），词内时间按字宽均分；其它语言保持整词。 */
    private fun wordTokens(
        textPaint: TextPaint,
        text: String,
        begin: Long,
        end: Long,
        splitCjk: Boolean,
    ): List<Token> {
        if (text.isEmpty()) return emptyList()
        if (!splitCjk) return listOf(Token(text = text, begin = begin, end = end))
        val parts = splitPlain(text)
        if (parts.size <= 1) return listOf(Token(text = text, begin = begin, end = end))
        val widths = parts.map { textPaint.measureText(it) }
        val total = widths.sum().coerceAtLeast(0.001f)
        val dur = (end - begin).coerceAtLeast(0L)
        var cum = 0f
        return parts.mapIndexed { i, part ->
            val b = begin + (dur * (cum / total)).toLong()
            cum += widths[i]
            Token(text = part, begin = b, end = begin + (dur * (cum / total)).toLong())
        }
    }

    private fun splitPlain(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (isCjk(ch)) {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.clear()
                }
                out += ch.toString()
            } else {
                sb.append(ch)
                if (ch == ' ') {
                    out += sb.toString()
                    sb.clear()
                }
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x2E80..0x9FFF) || (c in 0xAC00..0xD7AF) || (c in 0xF900..0xFAFF) || (c in 0xFF00..0xFFEF)
    }

    /**
     * 副行取数与单句模式一致（双声部 > 翻译 > 罗马音），对齐跟随主行；渐变进度只在
     * 副歌词自带逐字时间（secondaryWords）时启用，翻译/罗马音只渐显不走进度。
     */
    private fun buildSecondaryEntry(line: RichLyricLine, contentWidth: Int, align: LineAlign): SecondaryEntry? {
        if (!config.showSecondary) return null
        val secondary = line.secondary
        if (!secondary.isNullOrBlank()) {
            val secWords = line.secondaryWords
            if (!secWords.isNullOrEmpty()) {
                return SecondaryEntry(
                    model = buildLineModel(
                        textPaint = secPaint,
                        lineText = secondary,
                        lineBegin = line.begin,
                        lineEnd = line.end,
                        words = secWords,
                        contentWidth = contentWidth.toFloat(),
                        splitCjkWords = false,
                        align = align,
                        latinExtraPx = 0f,
                    ),
                    layout = null,
                )
            }
            return SecondaryEntry(model = null, layout = buildSecondaryLayout(secondary, contentWidth, align))
        }
        val translation = line.translation
        if (config.showTranslation && !translation.isNullOrBlank()) {
            return SecondaryEntry(model = null, layout = buildSecondaryLayout(translation, contentWidth, align))
        }
        val roma = line.roma
        if (config.showRoma && !roma.isNullOrBlank()) {
            return SecondaryEntry(model = null, layout = buildSecondaryLayout(roma, contentWidth, align))
        }
        return null
    }

    private fun buildSecondaryLayout(text: String, contentWidth: Int, align: LineAlign): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, secPaint, contentWidth)
            .setAlignment(
                when (align) {
                    LineAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    LineAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    LineAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                }
            )
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(2)
            .build()

    /** 歌名-作者名：与首句歌词合计最多 [INTRO_MAX_ROWS] 行，超出才省略；过长时圆点适当下移。 */
    private fun buildTitle(contentWidth: Int) {
        titleLayout = null
        titleShiftPx = 0f
        if (lines.isEmpty()) return
        val text = listOf(song?.name.orEmpty(), song?.artist.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        if (text.isBlank()) return
        val firstLineRows = models.firstOrNull()?.rowCount ?: 1
        val maxRows = (INTRO_MAX_ROWS - firstLineRows).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, titlePaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxRows)
            .build()
        titleLayout = layout
        val titleRowHeight = titlePaint.fontSpacing * 1.02f
        titleShiftPx = ((layout.height - titleRowHeight) / 2f).coerceAtLeast(0f)
    }

    // ---- 滚动与副歌词状态 ----

    private fun updateScroll(snap: Boolean) {
        if (lines.isEmpty()) return
        // SuperLyric 单句模式：只有一句，恒为当前句（画在大封面下方，不滚动、不停靠、无抢唱/副句）。
        if (singleLineMode) {
            overlapIndex = -1
            if (currentIndex != 0) {
                prevCurrentIndex = currentIndex
                zoomStartedAt = SystemClock.elapsedRealtime()
                currentIndex = 0
                scrollAnimating = false
                lastScroll = Float.NaN
                revealIndex = -1
                revealArmed = false
            }
            return
        }
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].begin <= positionMs) idx = i else break
        }
        // 抢唱重叠：前一句还在唱、后一句已开口 → 前一句仍居中为主句，后一句留在原位正常演唱（取消模糊），轮到它再滚到中间。
        var overlap = -1
        if (idx >= 1 && lines[idx - 1].end > positionMs) {
            overlap = idx
            idx = idx - 1
        }
        overlapIndex = overlap

        val now = SystemClock.elapsedRealtime()
        if (idx != currentIndex) {
            if (!snap && revealIndex == currentIndex && revealIndex >= 0) {
                collapseFrom = revealFactorAt(currentIndex, now)
                collapseIndex = currentIndex
                collapseStartedAt = now
            }
            // 当前句放大突出：新主句 1→1.03 放大；离开的旧主句缩回。
            prevCurrentIndex = currentIndex
            zoomStartedAt = now
            // 自带副句重新等待时机武装。
            revealIndex = -1
            revealArmed = false
            if (snap || currentIndex == Int.MIN_VALUE || lastScroll.isNaN()) {
                scrollAnimating = false
                lastScroll = Float.NaN
                collapseIndex = -1
            } else {
                scrollFrom = lastScroll
                scrollAnimating = true
                scrollStartedAt = now
            }
            currentIndex = idx
        }
        updateDockTarget(now, snap)
        armRevealIfDue(now, snap)
    }

    /**
     * 停靠目标随进度切换：首句已出（currentIndex>=0）→ 缩到角落（1）；进度在首句前 → 回到大封面引导卡（0）。
     * 回退做去抖：换歌瞬间只有 1~2 帧 idx<0（旧 song 与新 position 两条流错位）时不触发可见回退，
     * 避免闪一下大封面；真正停留在首句前才回退。换歌/尺寸变化（snap）立即就位。
     */
    private fun updateDockTarget(now: Long, snap: Boolean) {
        // 解锁(进入调整模式)时即使首句前也缩到角落，让出歌词、隐藏圆点；回锁后(仍首句前)恢复大封面+圆点。
        val wantDock = currentIndex >= 0 || unlocked
        if (wantDock) {
            revertPendingSince = 0L
            if (dockTarget != 1f) setDockTarget(1f, snap, now)
        } else if (dockTarget != 0f) {
            if (snap) {
                setDockTarget(0f, snap, now)
                revertPendingSince = 0L
            } else {
                if (revertPendingSince == 0L) revertPendingSince = now
                if (now - revertPendingSince >= DOCK_REVERT_DEBOUNCE_MS) {
                    setDockTarget(0f, snap, now)
                    revertPendingSince = 0L
                }
            }
        } else {
            revertPendingSince = 0L
        }
    }

    /** 设定停靠目标并启动过渡（snap=立即就位）。停靠到位时安排文字/歌名首显。 */
    private fun setDockTarget(target: Float, snap: Boolean, now: Long) {
        dockFrom = if (snap) target else coverDockProgress(now)
        dockTarget = target
        dockAnimAt = if (snap) now - DOCK_MS.toLong() else now
        if (target == 1f) {
            // 停靠到位时刻（snap=now、动画=now+DOCK_MS）：文字淡入、先锁定显示歌名-歌手再自动切时间。
            dockCompletedAt = if (snap) now else now + DOCK_MS.toLong()
            nameRevealAt = dockCompletedAt
            manualNameOverride = -1
            dockNameFrom = 1f
            dockNameTarget = 1f
            dockNameAnimAt = 0L
        } else {
            dockCompletedAt = 0L
        }
    }

    /** 自带副句按"时机"武装显示：抢唱时不显示；带逐字时间的和声=首字开口前 [SEC_LEAD_MS] 才顶入。 */
    private fun armRevealIfDue(now: Long, snap: Boolean) {
        if (overlapIndex >= 0) {
            // 抢唱占副句位时不显示当前句自带副句；若已显示则收起。
            if (revealIndex == currentIndex && revealIndex >= 0) {
                collapseFrom = revealFactorAt(currentIndex, now)
                collapseIndex = currentIndex
                collapseStartedAt = now
                revealIndex = -1
            }
            revealArmed = true
            return
        }
        if (revealArmed || currentIndex < 0) return
        val threshold = ownSecondaryThreshold(currentIndex)
        if (threshold == null) {
            revealArmed = true
            return
        }
        if (positionMs >= threshold) {
            revealArmed = true
            revealIndex = currentIndex
            revealStartedAt = if (snap) now - REVEAL_MS.toLong() else now
        }
    }

    /** 自带副句开始顶入的位置时刻：和声(带逐字时间)=首字开口前 [SEC_LEAD_MS]；翻译/罗马音=随主句出现。 */
    private fun ownSecondaryThreshold(i: Int): Long? {
        val sec = secEntries.getOrNull(i) ?: return null
        return if (sec.model != null) {
            (sec.model.tokens.firstOrNull()?.begin ?: lines[i].begin) - SEC_LEAD_MS
        } else {
            lines.getOrNull(i)?.begin ?: 0L
        }
    }

    /** 行缩放：当前句 1→1+[CURRENT_ZOOM] 放大突出，离开的旧主句缩回 1；其余（含抢唱句）保持 1。 */
    private fun lineScale(i: Int, now: Long): Float {
        if (i == currentIndex) {
            val t = easeOutCubic(((now - zoomStartedAt) / ZOOM_MS).coerceIn(0f, 1f))
            return 1f + CURRENT_ZOOM * t
        }
        if (i == prevCurrentIndex && now - zoomStartedAt < ZOOM_MS) {
            val t = easeOutCubic(((now - zoomStartedAt) / ZOOM_MS).coerceIn(0f, 1f))
            return (1f + CURRENT_ZOOM) - CURRENT_ZOOM * t
        }
        return 1f
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

    private fun revealFactorAt(i: Int, now: Long): Float {
        if (i < 0) return 0f
        if (i == revealIndex) {
            val t = ((now - revealStartedAt) / REVEAL_MS).coerceIn(0f, 1f)
            return smoothstep(t)
        }
        if (i == collapseIndex) {
            val t = ((now - collapseStartedAt) / REVEAL_MS).coerceIn(0f, 1f)
            return collapseFrom * (1f - smoothstep(t))
        }
        return 0f
    }

    private fun revealExtra(i: Int, now: Long): Float {
        val factor = revealFactorAt(i, now)
        if (factor <= 0f) return 0f
        val sec = secEntries.getOrNull(i) ?: return 0f
        return factor * (secGapPx + sec.height)
    }

    private fun topOf(i: Int, now: Long): Float {
        var top = baseTops[i]
        if (revealIndex in 0 until i) top += revealExtra(revealIndex, now)
        if (collapseIndex in 0 until i) top += revealExtra(collapseIndex, now)
        return top
    }

    private fun blockHeightOf(i: Int, now: Long): Float =
        models[i].mainHeight + revealExtra(i, now)

    private fun targetScrollFor(index: Int, now: Long): Float {
        if (index < 0) {
            // 歌名很长时圆点（连同整组）适当下移，给标题留出空间。
            return -(lineGapPx + dotSlotHeight / 2f) - titleShiftPx
        }
        val model = models[index]
        val mainH = model.mainHeight
        val limit = lineFollowLimitPx()
        // 超长句（换行后高度超过上下限）：当前句按实时进度在句内纵向滚动，显示正在读的那部分；
        // 其余按整块居中。
        if (index == currentIndex && model.rowCount > 1 && mainH > limit) {
            val offset = lerp(limit / 2f, mainH - limit / 2f, lineReadProgress(index))
            return topOf(index, now) + offset
        }
        return topOf(index, now) + blockHeightOf(index, now) / 2f
    }

    /** 单句可在视图内占用的纵向上限（整屏，不受安全区限制）：超过则当前句改为句内滚动跟随进度。 */
    private fun lineFollowLimitPx(): Float =
        (height.toFloat() * 0.86f).coerceAtLeast(textSizePx)

    /** 当前句已读进度（按首字 begin → 末字 end 的时间占比）。 */
    private fun lineReadProgress(index: Int): Float {
        val model = models[index]
        val first = model.tokens.firstOrNull()?.begin ?: lines.getOrNull(index)?.begin ?: 0L
        val last = model.tokens.lastOrNull()?.end ?: lines.getOrNull(index)?.end ?: first
        val dur = (last - first).coerceAtLeast(1L)
        return ((positionMs - first).toFloat() / dur).coerceIn(0f, 1f)
    }

    // ---- 绘制 ----

    private fun drawLineBlock(
        canvas: Canvas,
        i: Int,
        top: Float,
        blockHeight: Float,
        alphaF: Float,
        blur: BlurMaskFilter?,
        reveal: Float,
        recede: Float,
        scale: Float,
    ) {
        val model = models[i]
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        canvas.save()
        canvas.translate(0f, top)
        // 行缩放（当前句放大突出）叠加副句顶入时的整块缩小。居左/居右行锚定对应边：
        // 居左锁左边往右放大、居右锁右边往左放大，不向两侧侵占；居中才以中心放大。
        val blockScale = scale * (1f - BLOCK_SHRINK * reveal)
        if (blockScale != 1f) {
            val pivotX = when (lineAligns.getOrElse(i) { LineAlign.CENTER }) {
                LineAlign.LEFT -> 0f
                LineAlign.RIGHT -> contentWidth
                LineAlign.CENTER -> contentWidth / 2f
            }
            canvas.scale(blockScale, blockScale, pivotX, blockHeight / 2f)
        }
        paint.maskFilter = blur

        if (i == currentIndex || i == overlapIndex) {
            // 当前句、抢唱副句都在演唱 → 逐字渐变进度。
            drawKaraokeTokens(
                canvas = canvas,
                tokens = model.tokens,
                textPaint = paint,
                baseAlpha = BASE_DIM_ALPHA * alphaF,
                hiAlpha = 255f * alphaF,
                sink = true,
            )
        } else if (i < currentIndex) {
            // 已唱完的句子：高亮色打底 + 叠加白色，越往上越白，到隐藏边缘恰好全白。
            // 切到下一句时从高亮色平滑过渡到白，去掉了"先闪白再转灰"的硬切。
            drawWhiteningTokens(canvas, model.tokens, paint, 255f * alphaF, recede)
        } else {
            paint.shader = null
            paint.color = Color.WHITE
            paint.alpha = (255f * alphaF).roundToInt().coerceIn(0, 255)
            for (tok in model.tokens) {
                paint.textSize = tok.size
                canvas.drawText(tok.text, tok.x, tok.baseline + sinkPx, paint)
            }
            paint.textSize = textSizePx
        }

        if (reveal > 0f) {
            val sec = secEntries.getOrNull(i)
            if (sec != null) {
                val f = alphaF * reveal
                val secTop = model.mainHeight + secGapPx * reveal + (1f - reveal) * sec.height * 0.35f
                canvas.save()
                canvas.translate(0f, secTop)
                secPaint.maskFilter = blur
                if (sec.model != null) {
                    if (i == currentIndex) {
                        drawKaraokeTokens(
                            canvas = canvas,
                            tokens = sec.model.tokens,
                            textPaint = secPaint,
                            baseAlpha = SEC_BASE_ALPHA * f,
                            hiAlpha = SEC_HI_ALPHA * f,
                            sink = false,
                        )
                    } else if (i < currentIndex) {
                        // 与主歌词一致：已唱完的逐字副歌词也走高亮→白的渐变过渡。
                        drawWhiteningTokens(canvas, sec.model.tokens, secPaint, SEC_HI_ALPHA * f, recede)
                    } else {
                        secPaint.shader = null
                        secPaint.color = Color.WHITE
                        secPaint.alpha = (255f * SEC_ALPHA * f).roundToInt().coerceIn(0, 255)
                        for (tok in sec.model.tokens) {
                            canvas.drawText(tok.text, tok.x, tok.baseline, secPaint)
                        }
                    }
                } else if (sec.layout != null) {
                    secPaint.shader = null
                    secPaint.color = Color.WHITE
                    secPaint.alpha = (255f * SEC_ALPHA * f).roundToInt().coerceIn(0, 255)
                    sec.layout.draw(canvas)
                }
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /**
     * 已唱句绘制：高亮色（封面/封面渐变）打底，再叠加白色；[recede] 越大（离中心越远）
     * 白色越重，到隐藏边缘（recede→1）恰好全白。两遍绘制对纯色与渐变 shader 都适用。
     */
    private fun drawWhiteningTokens(
        canvas: Canvas,
        tokens: List<Token>,
        textPaint: TextPaint,
        baseAlpha: Float,
        recede: Float,
    ) {
        val baseSize = textPaint.textSize
        applyHighlight(textPaint, baseAlpha)
        for (tok in tokens) {
            textPaint.textSize = tok.size
            canvas.drawText(tok.text, tok.x, tok.baseline, textPaint)
        }
        textPaint.shader = null
        // 默认白字模式高亮本就是白，不再叠白（否则会抹掉已读透明度）；其它配色才需要"色→白"过渡。
        if (config.textColorMode != TextColorMode.DEFAULT) {
            val whiteBlend = recede.coerceIn(0f, 1f).pow(WHITE_FADE_EXPONENT)
            textPaint.color = Color.WHITE
            textPaint.alpha = (baseAlpha * whiteBlend).roundToInt().coerceIn(0, 255)
            for (tok in tokens) {
                textPaint.textSize = tok.size
                canvas.drawText(tok.text, tok.x, tok.baseline, textPaint)
            }
        }
        textPaint.textSize = baseSize
    }

    /** 渐变进度绘制：基色 + 高亮按词 clip 扫过；[sink] 时未唱文字下沉、唱到时回正。 */
    private fun drawKaraokeTokens(
        canvas: Canvas,
        tokens: List<Token>,
        textPaint: TextPaint,
        baseAlpha: Float,
        hiAlpha: Float,
        sink: Boolean,
    ) {
        val baseSize = textPaint.textSize
        for (tok in tokens) {
            textPaint.textSize = tok.size
            // 相对进度关闭：当前词到点即整体点亮（无词内平滑扫过）。
            val sung = positionMs >= tok.end || (!config.relativeProgress && positionMs > tok.begin)
            when {
                sung -> {
                    applyHighlight(textPaint, hiAlpha)
                    canvas.drawText(tok.text, tok.x, tok.baseline, textPaint)
                }
                positionMs <= tok.begin -> {
                    textPaint.shader = null
                    textPaint.color = Color.WHITE
                    textPaint.alpha = baseAlpha.roundToInt().coerceIn(0, 255)
                    val dy = if (sink) sinkPx else 0f
                    canvas.drawText(tok.text, tok.x, tok.baseline + dy, textPaint)
                }
                else -> {
                    val dy = if (sink) sinkOffset(tok) else 0f
                    textPaint.shader = null
                    textPaint.color = Color.WHITE
                    textPaint.alpha = baseAlpha.roundToInt().coerceIn(0, 255)
                    canvas.drawText(tok.text, tok.x, tok.baseline + dy, textPaint)
                    val dur = (tok.end - tok.begin).coerceAtLeast(1L)
                    val f = ((positionMs - tok.begin).toFloat() / dur).coerceIn(0f, 1f)
                    val front = tok.x + tok.width * f
                    if (config.gradientProgress) {
                        // 渐变软边：整词画高亮后用横向 alpha 渐变（DST_IN）在前沿羽化。
                        val soft = (textSizePx * GRADIENT_SOFT_EM).coerceAtMost(tok.width)
                        canvas.saveLayer(tok.x, tok.rowTop, tok.x + tok.width, tok.rowBottom + sinkPx, null)
                        applyHighlight(textPaint, hiAlpha)
                        canvas.drawText(tok.text, tok.x, tok.baseline + dy, textPaint)
                        maskPaint.shader = LinearGradient(
                            front - soft, 0f, front, 0f,
                            HIGHLIGHT_MASK_COLORS, null, Shader.TileMode.CLAMP,
                        )
                        maskPaint.xfermode = dstInXfermode
                        canvas.drawRect(tok.x, tok.rowTop, tok.x + tok.width, tok.rowBottom + sinkPx, maskPaint)
                        maskPaint.xfermode = null
                        maskPaint.shader = null
                        canvas.restore()
                    } else {
                        canvas.save()
                        canvas.clipRect(tok.x, tok.rowTop, front, tok.rowBottom + sinkPx)
                        applyHighlight(textPaint, hiAlpha)
                        canvas.drawText(tok.text, tok.x, tok.baseline + dy, textPaint)
                        canvas.restore()
                    }
                }
            }
        }
        textPaint.shader = null
        textPaint.textSize = baseSize
    }

    /** 未唱文字下沉，唱到时在词长（上限 320ms）内非线性回正。 */
    private fun sinkOffset(tok: Token): Float {
        val liftDur = (tok.end - tok.begin).coerceAtMost(LIFT_MS).coerceAtLeast(1L)
        val t = ((positionMs - tok.begin).toFloat() / liftDur).coerceIn(0f, 1f)
        val eased = 1f - (1f - t).pow(3)
        return (1f - eased) * sinkPx
    }

    /** 高亮上色：提取封面渐变色（≥2 色）走 shader，否则按提取封面颜色/默认白。 */
    private fun applyHighlight(textPaint: TextPaint, alpha: Float) {
        val shader = hiShader
        if (config.textColorMode == TextColorMode.COVER_GRADIENT && shader != null) {
            textPaint.color = Color.WHITE
            textPaint.shader = shader
        } else {
            textPaint.color = highlightColor()
            textPaint.shader = null
        }
        // 默认白字模式：已读文字降点透明度，透出背景色，而非死白。
        val a = if (config.textColorMode == TextColorMode.DEFAULT) alpha * READ_TEXT_ALPHA else alpha
        textPaint.alpha = a.roundToInt().coerceIn(0, 255)
    }

    private fun highlightColor(): Int =
        if (config.textColorMode == TextColorMode.DEFAULT) Color.WHITE else colors.highlight.toArgb()

    private fun refreshHighlightShader() {
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val cols = colors.highlightGradient
        val needed = config.textColorMode == TextColorMode.COVER_GRADIENT && cols.size >= 2
        if (!needed) {
            hiShader = null
            hiShaderKey = null
            return
        }
        val key = contentWidth to colors
        if (key == hiShaderKey) return
        hiShaderKey = key
        hiShader = LinearGradient(
            0f, 0f, contentWidth.toFloat(), 0f,
            IntArray(cols.size) { cols[it].toArgb() },
            null,
            Shader.TileMode.CLAMP,
        )
    }

    private fun blurFor(norm: Float): BlurMaskFilter? {
        val radius = (textSizePx * BLUR_MAX_EM * norm.pow(BLUR_EXPONENT)).roundToInt()
        if (radius <= 0) return null
        return blurCache.getOrPut(radius) { BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    /**
     * 首句前：歌名-作者名（不参与距离渐隐，随圆点同频在弱模糊透明与清晰间呼吸）
     * + 三个呼吸圆点；canvas 已在歌词坐标系。
     */
    private fun drawIntro(canvas: Canvas) {
        val firstBegin = lines.firstOrNull()?.begin ?: return
        if (firstBegin < DOT_MIN_INTRO_MS || positionMs >= firstBegin) return
        val remain = firstBegin - positionMs
        val exitK = if (remain >= DOT_EXIT_LEAD_MS) 1f else remain / DOT_EXIT_LEAD_MS.toFloat()
        val exit = exitK * exitK * (3f - 2f * exitK)
        if (exit <= 0f) return

        val pulse = (0.5 - 0.5 * cos(2.0 * Math.PI * positionMs / DOT_PULSE_PERIOD_MS)).toFloat()
        val dotsTop = -(lineGapPx + dotSlotHeight) - titleShiftPx

        val title = titleLayout
        if (title != null) {
            // 与圆点同频反相：点变大 → 标题清晰且略微变大；点变小 → 标题变模糊变小。
            val haze = 1f - pulse
            titlePaint.alpha = (255f * exit * (1f - TITLE_PULSE_ALPHA_DIP * haze)).roundToInt().coerceIn(0, 255)
            val blurRadius = (textSizePx * TITLE_PULSE_BLUR_EM * haze).roundToInt()
            titlePaint.maskFilter = if (blurRadius > 0) {
                blurCache.getOrPut(blurRadius) { BlurMaskFilter(blurRadius.toFloat(), BlurMaskFilter.Blur.NORMAL) }
            } else {
                null
            }
            val titleScale = 0.96f + 0.08f * pulse
            val contentW = (width - paddingLeft - paddingRight).toFloat()
            canvas.save()
            canvas.translate(0f, dotsTop - lineGapPx - title.height)
            canvas.scale(titleScale, titleScale, contentW / 2f, title.height / 2f)
            title.draw(canvas)
            canvas.restore()
            titlePaint.maskFilter = null
        }

        val scale = (0.9f + 0.3f * pulse) * exit
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        // 圆点横向位置跟随首句对齐（Apple Music 对唱时点在首句起始侧）。
        val baseSpacing = textSizePx * 0.45f
        val baseRadius = textSizePx * 0.14f
        val cx = when (lineAligns.firstOrNull() ?: LineAlign.CENTER) {
            LineAlign.LEFT -> baseSpacing + baseRadius
            LineAlign.CENTER -> contentWidth / 2f
            LineAlign.RIGHT -> contentWidth - baseSpacing - baseRadius
        }
        val cy = dotsTop + dotSlotHeight / 2f
        val radius = baseRadius * scale
        val spacing = baseSpacing * scale
        dotPaint.alpha = (255f * exit).roundToInt().coerceIn(0, 255)
        canvas.drawCircle(cx - spacing, cy, radius, dotPaint)
        canvas.drawCircle(cx, cy, radius, dotPaint)
        canvas.drawCircle(cx + spacing, cy, radius, dotPaint)
    }

    /**
     * 无歌词：封面 + 歌名 + 歌手居中展示（同歌曲信息模式）。有封面则"封面 + 文字"整行居中、
     * 旋转封面保持旋转；无封面则歌名/歌手居中。不停靠、不显示圆点。
     */
    private fun drawNoLyricCard(canvas: Canvas, now: Long) {
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        if (contentWidth <= 0f || contentHeight <= 0f) return

        val gap = textSizePx * INTRO_GAP_EM
        val nameW = introNameLayout?.let { layoutWidth(it) } ?: 0f
        val artistW = introArtistLayout?.let { layoutWidth(it) } ?: 0f
        val textW = max(nameW, artistW)
        // 无歌词时封面居中；展开面板时上移到「首句前大封面」高度（与有歌词一致），给下方按键让位。
        // SuperLyric 单句模式：封面恒定在大封面（顶部）位置，避免"无句→有句"时封面从居中跳到顶部。
        val introCenterY = contentTop + contentHeight * INTRO_TOP_FRAC + introCoverSizePx() / 2f
        val centerY = if (singleLineMode) introCenterY else lerp(contentTop + contentHeight / 2f, introCenterY, panelProgress)

        val bmp = coverBitmap
        if (bmp != null && config.cover != CoverPosition.NONE) {
            updateCoverRotation(now)
            val rotationDeg = if (config.coverShape == CoverShape.CIRCLE_ROTATE) coverRotation else 0f
            // 封面随播放/暂停缩放（播放 1.1、暂停 1），同歌曲信息模式。
            val cover = introCoverSizePx() * currentCoverScale(now)
            val rowW = cover + if (textW > 0f) gap + textW else 0f
            val rowLeft = contentLeft + ((contentWidth - rowW) / 2f).coerceAtLeast(0f)
            val coverCx = rowLeft + cover / 2f
            val textLeft = rowLeft + cover + gap
            // 封面命中区：无歌词卡的居中封面也可点击放大成控制面板。
            val hitPad = dp(12f)
            coverHitRect.set(
                coverCx - cover / 2f - hitPad,
                centerY - cover / 2f - hitPad,
                coverCx + cover / 2f + hitPad,
                centerY + cover / 2f + hitPad,
            )
            // 记录封面槽位几何（飞出起点 / 飞入目标）：无歌词卡封面居中、非停靠。
            lastCoverCx = coverCx
            lastCoverCy = centerY
            lastCoverSize = cover
            lastCoverDocked = false
            // 换歌封面过渡期间，居中封面也交给飞入/飞出层（底板快照亦不画），避免与切换动画重影。
            if (!drawingSnapshot && !coverTransitionActive(now)) {
                drawCoverBitmap(canvas, bmp, coverCx, centerY, cover, rotationDeg)
            }
            drawIntroText(canvas, textLeft, centerY, 1f)
            val scaleAnimating = coverScaleStartedAt != 0L && now - coverScaleStartedAt < DOCK_SCALE_MS.toLong()
            if ((config.coverShape == CoverShape.CIRCLE_ROTATE && playing) || scaleAnimating || coverTransitionActive(now)) {
                postInvalidateOnAnimation()
            }
        } else {
            val textLeft = contentLeft + ((contentWidth - textW) / 2f).coerceAtLeast(0f)
            drawIntroText(canvas, textLeft, centerY, 1f)
        }
    }

    // ---- 封面引导卡 / 角落停靠 + 时间 ----

    /**
     * 有封面时的引导与停靠：首句前画"封面 + 歌名 + 歌手"引导卡（封面固定居左、上靠贴安全区，
     * 无放大/模糊呼吸），三个圆点在首句歌词上方；首句出来后封面非线性缩到**右上角**（无视安全区、
     * 贴角；旋转封面保持旋转），歌名/歌手/圆点淡出；停靠完成后在封面**左侧**先显示"歌名-歌手"
     * （定宽字号、装不下则居右跑马灯滚动），NAME_SHOW_MS 后切到 24 小时时间；点封面又切回歌名。
     * 引导卡/歌词用内容区（含安全区内边距）定位，角落封面用视图原始边界（贴真实角）。
     */
    private fun drawCoverWidget(canvas: Canvas, now: Long) {
        val bmp = coverBitmap ?: return
        val rawW = width.toFloat()
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        if (contentWidth <= 0f || contentHeight <= 0f) return

        updateCoverRotation(now)
        val rotationDeg = if (config.coverShape == CoverShape.CIRCLE_ROTATE) coverRotation else 0f

        val p = coverDockProgress(now)
        // 封面随播放/暂停缩放（播放 1.1、暂停 1）：引导卡大封面与角落小封面一致（同歌曲信息模式）。
        val coverScale = currentCoverScale(now)
        val introCover = introCoverSizePx() * coverScale
        val dockCover = dockCoverSizePx() * coverScale
        val coverSize = lerp(introCover, dockCover, p)

        // 引导卡几何：封面固定居左、上靠（内容坐标）；封面 + 间距 + 歌名/歌手文字块。
        val gap = textSizePx * INTRO_GAP_EM
        val nameW = introNameLayout?.let { layoutWidth(it) } ?: 0f
        val artistW = introArtistLayout?.let { layoutWidth(it) } ?: 0f
        val textW = max(nameW, artistW)
        val rowW = introCover + if (textW > 0f) gap + textW else 0f
        val rowLeft = contentLeft + ((contentWidth - rowW) / 2f).coerceAtLeast(0f)
        val rowTop = contentTop + contentHeight * INTRO_TOP_FRAC
        val introCoverCx = rowLeft + introCover / 2f
        val textLeft = rowLeft + introCover + gap
        val introCoverCy = rowTop + introCover / 2f

        // 角落停靠几何：右上角，贴真实角（视图原始边界，无视安全区内边距），按缩放后尺寸锚定。
        val margin = dp(DOCK_MARGIN_DP)
        val dockCoverCx = rawW - margin - dockCover / 2f
        val dockCoverCy = margin + dockCover / 2f

        val coverCx = lerp(introCoverCx, dockCoverCx, p)
        val coverCy = lerp(introCoverCy, dockCoverCy, p)

        // 封面命中区：始终跟随当前绘制的封面（引导卡大封面或角落小封面），点击放大成控制面板。
        val hitPad = dp(12f)
        coverHitRect.set(
            coverCx - coverSize / 2f - hitPad,
            coverCy - coverSize / 2f - hitPad,
            coverCx + coverSize / 2f + hitPad,
            coverCy + coverSize / 2f + hitPad,
        )
        // 文字带命中区（点击切换歌名/时间）：仅停靠后、封面左侧的文字区。
        coverDocked = p > 0.6f
        if (coverDocked) {
            textHitRect.set(
                contentLeft,
                coverCy - coverSize / 2f - hitPad,
                coverCx - coverSize / 2f - hitPad,
                coverCy + coverSize / 2f + hitPad,
            )
        }

        // 记录封面槽位几何（飞出起点 / 飞入目标用）。
        lastCoverCx = coverCx
        lastCoverCy = coverCy
        lastCoverSize = coverSize
        lastCoverDocked = coverDocked

        // 换歌封面过渡期间，槽位上的封面图片交给飞入/飞出层；底板快照里也不画封面。
        if (!drawingSnapshot && !coverTransitionActive(now)) {
            drawCoverBitmap(canvas, bmp, coverCx, coverCy, coverSize, rotationDeg)
        }

        // 单击封面弹"双击打开控制页"提示：大封面画在圆点行、小封面画在时间处，几秒后自动淡出。
        val hintAlpha = coverHintAlpha(now)

        val introAlpha = (1f - p).coerceIn(0f, 1f)
        if (introAlpha > 0.01f) {
            drawIntroText(canvas, textLeft, introCoverCy, introAlpha)
            // 圆点跟随首句对齐、位于第一句歌词上方；面板模式下不画圆点（与首句提示无关）。
            if (!panelMode) {
                val firstLineTop = height / 2f - lastScroll
                val dotsY = firstLineTop - textSizePx * 0.55f
                if (hintAlpha > 0.01f && !coverDocked) {
                    // 大封面单击：圆点行改画提示。
                    drawCoverHint(canvas, contentLeft + contentWidth / 2f, dotsY, hintAlpha * introAlpha, centered = true)
                } else if (!singleLineMode) {
                    // SuperLyric 单句模式不画圆点（下方改画当前句歌词）。
                    drawIntroDots(canvas, contentLeft, contentWidth, dotsY, introAlpha)
                }
            }
        }

        // 角落歌名/时间：随停靠淡入，随解除停靠（×p）一并淡出，避免硬切（解决"太僵硬"）。
        val textAlpha = clockFadeAlpha(now) * p
        if (textAlpha > 0.01f) {
            // 文字区在封面左侧：右边界=封面左缘-间距，左边界=内容左缘。
            val rightEdge = coverCx - coverSize / 2f - dp(CLOCK_GAP_DP)
            if (hintAlpha > 0.01f && coverDocked) {
                // 小封面单击：时间处改画提示（居右，取代歌名/时间）。
                drawCoverHint(canvas, rightEdge, coverCy, textAlpha * hintAlpha, centered = false)
            } else if (unlocked) {
                // 解锁后封面左侧提示"歌词已解锁 / 可滑动调整进度"，取代歌名/时间。
                drawUnlockHint(canvas, contentLeft, rightEdge, coverCy, textAlpha)
            } else {
                // 歌名↔时间：自动模式歌名显示 NAME_SHOW_MS 后切时间；点击切换并锁定（手动覆盖）。
                val showName = when (manualNameOverride) {
                    1 -> true
                    0 -> false
                    else -> now < nameRevealAt + NAME_SHOW_MS
                }
                retargetDockName(showName, now)
                val nameFactor = currentDockNameFactor(now)
                drawDockText(canvas, contentLeft, rightEdge, coverCy, textAlpha, nameFactor, now)
            }
        }
    }

    /** 设定歌名/时间过渡目标（true=歌名 1，false=时间 0），目标变化才重启过渡。 */
    private fun retargetDockName(show: Boolean, now: Long) {
        val t = if (show) 1f else 0f
        if (t == dockNameTarget) return
        dockNameFrom = currentDockNameFactor(now)
        dockNameTarget = t
        dockNameAnimAt = now
    }

    /** 当前歌名因子：1=歌名、0=时间，from→target 在 NAME_TIME_SWITCH_MS 内 smoothstep 过渡。 */
    private fun currentDockNameFactor(now: Long): Float {
        if (dockNameAnimAt == 0L) return dockNameTarget
        val k = ((now - dockNameAnimAt) / NAME_TIME_SWITCH_MS).coerceIn(0f, 1f)
        return lerp(dockNameFrom, dockNameTarget, smoothstep(k))
    }

    /** 角落文字区（封面左侧，居右）：歌名-歌手（装不下跑马灯）与时间按 [nameFactor] 交叉淡入淡出。 */
    private fun drawDockText(canvas: Canvas, leftBound: Float, rightEdge: Float, centerY: Float, alpha: Float, nameFactor: Float, now: Long) {
        if (rightEdge - leftBound <= 0f) return
        val textSize = dockCoverSizePx() * CLOCK_TEXT_FRAC
        clockPaint.textSize = textSize
        val fm = clockPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val clipTop = centerY - textSize
        val clipBottom = centerY + textSize

        val nameAlpha = nameFactor * alpha
        val timeAlpha = (1f - nameFactor) * alpha

        if (nameAlpha > 0.01f) {
            val text = nameArtistText()
            val tw = clockPaint.measureText(text)
            val avail = rightEdge - leftBound
            clockPaint.alpha = (255f * nameAlpha).roundToInt().coerceIn(0, 255)
            canvas.save()
            canvas.clipRect(leftBound, clipTop, rightEdge, clipBottom)
            if (tw <= avail) {
                canvas.drawText(text, rightEdge - tw, baseline, clockPaint)
            } else {
                // 居右起步、向左滚动循环（无省略号）。
                val period = tw + dp(MARQUEE_GAP_DP)
                val travel = ((now - nameRevealAt) / 1000f * dp(MARQUEE_SPEED_DP)) % period
                val x = rightEdge - tw - travel
                canvas.drawText(text, x, baseline, clockPaint)
                canvas.drawText(text, x + period, baseline, clockPaint)
            }
            canvas.restore()
        }
        if (timeAlpha > 0.01f) {
            val text = currentClockText()
            val tw = clockPaint.measureText(text)
            clockPaint.alpha = (255f * timeAlpha).roundToInt().coerceIn(0, 255)
            canvas.drawText(text, rightEdge - tw, baseline, clockPaint)
        }
    }

    /** 单击封面触发提示（双击窗口内无第二击才弹）。 */
    private fun triggerCoverHint() {
        coverTapAt = 0L
        coverHintAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    /** "双击打开控制页"提示的当前透明度：快速淡入、末段淡出；过期即清零（停止重绘）。 */
    private fun coverHintAlpha(now: Long): Float {
        if (coverHintAt == 0L) return 0f
        val t = (now - coverHintAt).toFloat()
        if (t >= COVER_HINT_MS) {
            coverHintAt = 0L
            return 0f
        }
        return min(t / 150f, (COVER_HINT_MS - t) / 450f).coerceIn(0f, 1f)
    }

    /** 画"双击打开控制页"提示：[centered]=以 x 为中心（大封面圆点行），否则以 x 为右缘居右（小封面时间处）。 */
    private fun drawCoverHint(canvas: Canvas, x: Float, centerY: Float, alpha: Float, centered: Boolean) {
        clockPaint.textSize = textSizePx * 0.5f
        clockPaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
        val tw = clockPaint.measureText(COVER_HINT_TEXT)
        val fm = clockPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val left = if (centered) x - tw / 2f else x - tw
        canvas.drawText(COVER_HINT_TEXT, left, baseline, clockPaint)
        clockPaint.alpha = 255
    }

    /** 解锁后封面左侧提示：两行右对齐「歌词已解锁 / 可滑动调整进度」，字号自适应可用宽度。 */
    private fun drawUnlockHint(canvas: Canvas, leftBound: Float, rightEdge: Float, centerY: Float, alpha: Float) {
        val avail = rightEdge - leftBound
        if (avail <= 0f) return
        val l1 = "歌词已解锁"
        val l2 = "可滑动调整进度"
        var size = dockCoverSizePx() * CLOCK_TEXT_FRAC * 0.8f
        clockPaint.textSize = size
        val longest = max(clockPaint.measureText(l1), clockPaint.measureText(l2))
        if (longest > avail) {
            size *= avail / longest
            clockPaint.textSize = size
        }
        val fm = clockPaint.fontMetrics
        val lineH = -fm.ascent + fm.descent
        val lineGap = size * 0.28f
        val blockH = lineH * 2f + lineGap
        clockPaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
        canvas.save()
        canvas.clipRect(leftBound, centerY - blockH, rightEdge, centerY + blockH)
        var baseline = centerY - blockH / 2f - fm.ascent
        canvas.drawText(l1, rightEdge - clockPaint.measureText(l1), baseline, clockPaint)
        baseline += lineH + lineGap
        canvas.drawText(l2, rightEdge - clockPaint.measureText(l2), baseline, clockPaint)
        canvas.restore()
    }

    private fun nameArtistText(): String {
        val text = listOf(song?.name.orEmpty(), song?.artist.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        return text.ifBlank { "未知歌曲" }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inCover = !coverHitRect.isEmpty && coverHitRect.contains(event.x, event.y)
        // 解锁后封面左侧是"已解锁"提示而非歌名/时间，禁用文字带切换。
        val inText = !unlocked && coverDocked && !textHitRect.isEmpty && textHitRect.contains(event.x, event.y)
        when (event.actionMasked) {
            // 必须消费 DOWN 才能收到 UP（非 clickable 的 View 默认 DOWN 返回 false 就收不到后续事件）。
            MotionEvent.ACTION_DOWN -> {
                if (inCover || inText) return true
                // SuperLyric 单句模式：无整首歌词，长按解锁 + 拖动调进度没意义，直接不接管歌词区手势。
                if (singleLineMode) return false
                if (panelMode || models.isEmpty()) return false
                pressDownX = event.x
                pressDownY = event.y
                // 歌词区按下：已解锁→抓取准备自由滑动/点击跳转；锁定→蓄力长按解锁。
                if (unlocked) grabScrub(event) else armLongPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressArmAt != 0L) {
                    // 蓄力中移动超阈值即取消（避免把拖动误判为长按；背屏被遮挡产生的滑动也不会误解锁）。
                    if (abs(event.x - pressDownX) > dp(UNLOCK_MOVE_CANCEL_DP) ||
                        abs(event.y - pressDownY) > dp(UNLOCK_MOVE_CANCEL_DP)
                    ) {
                        cancelUnlockHold()
                    }
                    return true
                }
                if (scrubGrabbed) {
                    velocityTracker?.addMovement(event)
                    val dy = event.y - lastTouchY
                    lastTouchY = event.y
                    if (!movedScrub && abs(event.y - pressDownY) > dp(6f)) movedScrub = true
                    if (movedScrub) moveScrub(dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (inCover) {
                    if (panelMode) {
                        // 面板已展开：单击封面即返回歌词（无需双击）。
                        onCoverTap?.invoke()
                    } else {
                        // 非面板态：双击放大成控制面板；单击只弹"双击打开控制页"提示（圆点行/时间处）。
                        val tnow = SystemClock.elapsedRealtime()
                        if (tnow - coverTapAt <= COVER_DOUBLE_TAP_MS) {
                            coverTapAt = 0L
                            coverHintAt = 0L
                            handler.removeCallbacks(coverSingleTapRunnable)
                            onCoverTap?.invoke()
                        } else {
                            coverTapAt = tnow
                            handler.removeCallbacks(coverSingleTapRunnable)
                            handler.postDelayed(coverSingleTapRunnable, COVER_DOUBLE_TAP_MS)
                        }
                    }
                    performClick()
                    return true
                }
                if (inText) {
                    // 点文字带：在歌名与时间之间切换并锁定（手动覆盖，不再自动回切）。
                    toggleDockName()
                    performClick()
                    return true
                }
                if (pressArmAt != 0L) {
                    cancelUnlockHold()
                    return true
                }
                if (scrubGrabbed) {
                    scrubGrabbed = false
                    if (movedScrub) {
                        // 拖动松手：按抬手速度起惯性滑动（丝滑停下），**不 seek**——要点击某句才跳转。
                        velocityTracker?.addMovement(event)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vy = velocityTracker?.yVelocity ?: 0f
                        recycleVelocity()
                        startFling(-vy)
                        scheduleRelock()
                    } else {
                        // 点击某句歌词：跳转到该句并起播。
                        recycleVelocity()
                        tapSeek(event.y)
                    }
                    performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelUnlockHold()
                if (scrubGrabbed) {
                    scrubGrabbed = false
                    recycleVelocity()
                    if (movedScrub) scheduleRelock() else exitBrowse()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---- 锁定 / 长按解锁 / 拖动调整进度 ----

    /** 锁定态按下歌词：开始 2s 蓄力，到点 [doUnlock] 解锁。 */
    private fun armLongPress() {
        pressArmAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(unlockRunnable)
        handler.postDelayed(unlockRunnable, UNLOCK_HOLD_MS)
        invalidate()
    }

    private fun cancelUnlockHold() {
        if (pressArmAt == 0L) return // 未蓄力
        pressArmAt = 0L
        handler.removeCallbacks(unlockRunnable)
        invalidate()
    }

    private fun doUnlock() {
        if (pressArmAt == 0L) return // 已被取消
        pressArmAt = 0L
        unlocked = true
        lockAnimAt = SystemClock.elapsedRealtime()
        lockAnimOpening = true
        scheduleRelock()
        applyUnlockDock(SystemClock.elapsedRealtime())
        invalidate()
    }

    private fun doRelock() {
        if (!unlocked) return
        unlocked = false
        // 平滑滚回直播位置：从当前浏览位置启动一次滚动动画（而非硬切）。
        if (scrubbing && !lastScroll.isNaN()) {
            scrollFrom = lastScroll
            scrollAnimating = true
            scrollStartedAt = SystemClock.elapsedRealtime()
        }
        exitBrowse()
        applyUnlockDock(SystemClock.elapsedRealtime())
        lockAnimAt = SystemClock.elapsedRealtime()
        lockAnimOpening = false
        invalidate()
    }

    /**
     * 解锁=进入调整模式：首句前也立即把封面缩到角落（让出歌词、圆点随 introAlpha 淡出）；
     * 回锁后若仍在首句前则恢复大封面+圆点。立即生效，不依赖 bind 每帧驱动（暂停时 bind 可能不刷）。
     */
    private fun applyUnlockDock(now: Long) {
        val wantDock = currentIndex >= 0 || unlocked
        revertPendingSince = 0L
        if (wantDock) {
            if (dockTarget != 1f) setDockTarget(1f, snap = false, now)
        } else if (dockTarget != 0f) {
            setDockTarget(0f, snap = false, now)
        }
    }

    private fun scheduleRelock() {
        handler.removeCallbacks(relockRunnable)
        handler.postDelayed(relockRunnable, UNLOCK_IDLE_RELOCK_MS)
    }

    /** 解锁后按下歌词：抓取当前滚动位置作自由滑动基准（松手前不回锁），起 VelocityTracker。 */
    private fun grabScrub(event: MotionEvent) {
        scrubGrabbed = true
        movedScrub = false
        flinging = false
        scrubbing = true
        lastTouchY = event.y
        val now = SystemClock.elapsedRealtime()
        grabScroll = if (lastScroll.isNaN()) targetScrollFor(currentIndex, now) else lastScroll
        scrubScroll = grabScroll
        handler.removeCallbacks(relockRunnable)
        recycleVelocity()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
    }

    private fun moveScrub(dy: Float) {
        val now = SystemClock.elapsedRealtime()
        val (lo, hi) = scrubBounds(now)
        // 手指下移→更早的歌词（内容随手指走），任意位置自由滑动。
        scrubScroll = (scrubScroll - dy).coerceIn(lo, hi)
        reportScrubCenter(now)
        invalidate()
    }

    /** 惯性滑动每帧推进：指数摩擦衰减，到边界/速度过小即停。 */
    private fun advanceFling(now: Long) {
        if (lastFlingAt == 0L) {
            lastFlingAt = now
            return
        }
        val dt = ((now - lastFlingAt) / 1000f).coerceIn(0f, 0.05f)
        lastFlingAt = now
        val (lo, hi) = scrubBounds(now)
        scrubScroll += flingVel * dt
        when {
            scrubScroll <= lo -> { scrubScroll = lo; flinging = false }
            scrubScroll >= hi -> { scrubScroll = hi; flinging = false }
            else -> {
                flingVel *= exp(-FLING_FRICTION * dt)
                if (abs(flingVel) < FLING_STOP_VELOCITY) flinging = false
            }
        }
        reportScrubCenter(now)
    }

    private fun startFling(vel: Float) {
        if (abs(vel) < FLING_MIN_VELOCITY) {
            flinging = false // 速度太小不滑，停在原地（仍处浏览态，等点击/回锁）
            return
        }
        flingVel = vel
        flinging = true
        lastFlingAt = 0L
        invalidate()
    }

    /** 上报「正中那句」的时间给 Compose（拖动/惯性中都刷新）。 */
    private fun reportScrubCenter(now: Long) {
        val idx = nearestIndexTo(scrubScroll, now)
        scrubCenterIndex = idx
        onScrubChange?.invoke(true, lines.getOrNull(idx)?.begin ?: 0L)
    }

    /** 点击某句歌词：退出浏览 + 跳转到该句并起播。 */
    private fun tapSeek(y: Float) {
        val now = SystemClock.elapsedRealtime()
        val contentY = y - height / 2f + grabScroll
        val idx = nearestIndexTo(contentY, now)
        val begin = lines.getOrNull(idx)?.begin
        exitBrowse()
        if (begin != null) {
            applyLocalSeek(begin)
            onSeek?.invoke(begin)
        }
        scheduleRelock()
    }

    /** seek 后立即按目标进度渲染（防直播进度未追上前回弹），并触发滚动动画到目标句。 */
    private fun applyLocalSeek(ms: Long) {
        pendingSeekMs = ms
        pendingSeekAt = SystemClock.elapsedRealtime()
        positionMs = ms
        if (!scrubbing) updateScroll(snap = false)
        invalidate()
    }

    /** 退出手动浏览（停止惯性、回到直播跟随），隐藏左侧时间。 */
    private fun exitBrowse() {
        scrubbing = false
        flinging = false
        scrubGrabbed = false
        movedScrub = false
        scrubCenterIndex = -1
        onScrubChange?.invoke(false, 0L)
        invalidate()
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** 把锁视觉参数上报给 Compose（蓄力进度环/解锁开锁/回锁合锁动画）；仅在 true→false 时补发一次隐藏。 */
    private fun reportLockVisual(now: Long) {
        val cb = onLockVisual ?: return
        if (pressArmAt != 0L) {
            val p = ((now - pressArmAt) / UNLOCK_HOLD_MS.toFloat()).coerceIn(0f, 1f)
            cb(true, p, 1f, 0f, 0.5f + 0.5f * p)
            lockReported = true
            return
        }
        if (lockAnimAt != 0L) {
            val t = ((now - lockAnimAt) / LOCK_ANIM_MS).coerceIn(0f, 1f)
            if (t < 1f) {
                val open = if (lockAnimOpening) smoothstep(t) else 1f - smoothstep(t)
                val fade = 1f - t
                cb(true, 1f, fade, open, fade)
                lockReported = true
                return
            }
            lockAnimAt = 0L
        }
        if (lockReported) {
            cb(false, 0f, 0f, 0f, 0f)
            lockReported = false
        }
    }

    private fun toggleDockName() {
        val showingName = dockNameTarget >= 0.5f
        if (showingName) {
            manualNameOverride = 0
        } else {
            manualNameOverride = 1
            nameRevealAt = SystemClock.elapsedRealtime() // 跑马灯从居右重新起步
        }
        invalidate()
    }

    /** 与某滚动位置最接近的句下标（句中心随下标单调递增，越过最近点后距离只增，可提前结束）。 */
    private fun nearestIndexTo(scrollY: Float, now: Long): Int {
        if (models.isEmpty()) return 0
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in models.indices) {
            val c = topOf(i, now) + models[i].mainHeight / 2f
            val d = abs(c - scrollY)
            if (d <= bestD) {
                bestD = d
                best = i
            } else {
                break
            }
        }
        return best
    }

    /** 拖动滚动可达范围：首句中心 → 末句中心。 */
    private fun scrubBounds(now: Long): Pair<Float, Float> {
        if (models.isEmpty()) return 0f to 0f
        val lo = topOf(0, now) + models.first().mainHeight / 2f
        val hi = topOf(models.lastIndex, now) + models.last().mainHeight / 2f
        return lo to hi
    }

    /** 引导卡的歌名（上）/歌手（下）文字块，竖直居中对齐封面中心。 */
    private fun drawIntroText(canvas: Canvas, textLeft: Float, coverCy: Float, alpha: Float) {
        val name = introNameLayout
        val artist = introArtistLayout
        val nameH = name?.height ?: 0
        val artistH = artist?.height ?: 0
        val innerGap = if (name != null && artist != null) dp(INTRO_NAME_ARTIST_GAP_DP) else 0f
        val blockH = nameH + innerGap + artistH
        var y = coverCy - blockH / 2f
        if (name != null) {
            introNamePaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(textLeft, y)
            name.draw(canvas)
            canvas.restore()
            y += nameH + innerGap
        }
        if (artist != null) {
            introArtistPaint.alpha = (255f * INTRO_ARTIST_ALPHA * alpha).roundToInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(textLeft, y)
            artist.draw(canvas)
            canvas.restore()
        }
    }

    /** 三个呼吸圆点（首句歌词上方），横向跟随首句对齐，随停靠淡出。 */
    private fun drawIntroDots(canvas: Canvas, leftBound: Float, contentWidth: Float, centerY: Float, alpha: Float) {
        val pulse = (0.5 - 0.5 * cos(2.0 * Math.PI * positionMs / DOT_PULSE_PERIOD_MS)).toFloat()
        val scale = 0.9f + 0.3f * pulse
        val baseSpacing = textSizePx * 0.45f
        val baseRadius = textSizePx * 0.14f
        val cx = leftBound + when (lineAligns.firstOrNull() ?: LineAlign.CENTER) {
            LineAlign.LEFT -> baseSpacing + baseRadius
            LineAlign.CENTER -> contentWidth / 2f
            LineAlign.RIGHT -> contentWidth - baseSpacing - baseRadius
        }
        val radius = baseRadius * scale
        val spacing = baseSpacing * scale
        dotPaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
        canvas.drawCircle(cx - spacing, centerY, radius, dotPaint)
        canvas.drawCircle(cx, centerY, radius, dotPaint)
        canvas.drawCircle(cx + spacing, centerY, radius, dotPaint)
    }

    /** 角落小封面缩放：from→target（1↔1.1）的 easeOut 插值。 */
    private fun currentCoverScale(now: Long): Float {
        if (coverScaleStartedAt == 0L) return coverScaleTarget
        val t = ((now - coverScaleStartedAt) / DOCK_SCALE_MS).coerceIn(0f, 1f)
        return lerp(coverScaleFrom, coverScaleTarget, easeOutCubic(t))
    }

    /** 封面绘制：圆角(方形)/圆形裁剪 + 旋转，源图按短边居中裁成正方形。 */
    /** 换歌封面过渡是否进行中（飞出 / 飞入 / 等新封面加载）。 */
    private fun coverTransitionActive(now: Long): Boolean {
        val exiting = coverExitBitmap != null && now - coverExitAt < COVER_FLY_MS
        val entering = coverEnterAt != 0L && now - coverEnterAt < COVER_FLY_MS
        return exiting || entering || coverEnterPending
    }

    /**
     * 换歌封面飞出 / 飞入（绝对坐标，独立于整页底板上滑）：
     * 旧封面向上飞出整屏——大封面态非线性缩到 [COVER_EXIT_SCALE] 飞出，角落停靠态不缩直接飞出；
     * 新封面加载后从上方非线性飞入到封面槽位（位置 + 大小）；等新封面超时则用当前封面飞入兜底。
     */
    private fun drawCoverTransition(canvas: Canvas, now: Long) {
        if (drawingSnapshot || config.cover == CoverPosition.NONE) return
        val rotationDeg = if (config.coverShape == CoverShape.CIRCLE_ROTATE) coverRotation else 0f

        // 旧封面飞出。
        val exit = coverExitBitmap
        if (exit != null) {
            if (exit.isRecycled || now - coverExitAt >= COVER_FLY_MS) {
                coverExitBitmap = null
            } else {
                val t = ((now - coverExitAt) / COVER_FLY_MS).coerceIn(0f, 1f)
                val e = t * t // 非线性：加速飞出
                val scale = if (coverExitDocked) 1f else lerp(1f, COVER_EXIT_SCALE, e)
                val cy = lerp(coverExitFromCy, -coverExitFromSize, e) // 中心移出屏幕顶部
                drawCoverBitmap(canvas, exit, coverExitFromCx, cy, coverExitFromSize * scale, rotationDeg)
            }
        }

        // 等新封面加载超时：用当前封面飞入兜底，避免封面一直空着。
        if (coverEnterPending && coverBitmap != null && now - coverExitAt > COVER_ENTER_MAX_WAIT_MS) {
            coverEnterAt = now
            coverEnterPending = false
        }

        // 新封面飞入。
        if (coverEnterAt != 0L) {
            val newCover = coverBitmap
            if (newCover == null || now - coverEnterAt >= COVER_FLY_MS) {
                coverEnterAt = 0L
            } else if (lastCoverSize > 0f) {
                val t = ((now - coverEnterAt) / COVER_FLY_MS).coerceIn(0f, 1f)
                val e = easeOutCubic(t) // 非线性：减速到位
                val cy = lerp(-lastCoverSize, lastCoverCy, e)
                val size = lastCoverSize * lerp(COVER_EXIT_SCALE, 1f, e)
                drawCoverBitmap(canvas, newCover, lastCoverCx, cy, size, rotationDeg)
            }
        }
    }

    private fun drawCoverBitmap(canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, size: Float, rotationDeg: Float) {
        if (size <= 0f || bmp.width <= 0 || bmp.height <= 0) return
        val half = size / 2f
        val l = cx - half
        val t = cy - half
        val r = cx + half
        val b = cy + half
        canvas.save()
        if (rotationDeg != 0f) canvas.rotate(rotationDeg, cx, cy)
        coverClipPath.reset()
        if (config.coverShape == CoverShape.SQUARE) {
            val radius = dp(4f)
            coverClipPath.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW)
        } else {
            coverClipPath.addCircle(cx, cy, half, Path.Direction.CW)
        }
        canvas.clipPath(coverClipPath)
        val side = min(bmp.width, bmp.height)
        val sx = (bmp.width - side) / 2
        val sy = (bmp.height - side) / 2
        coverSrc.set(sx, sy, sx + side, sy + side)
        coverDst.set(l, t, r, b)
        canvas.drawBitmap(bmp, coverSrc, coverDst, coverPaint)
        canvas.restore()
    }

    /** 旋转封面角度按墙钟增量累计，仅播放中推进（与单句模式一致 16s/圈）。 */
    private fun updateCoverRotation(now: Long) {
        if (config.coverShape != CoverShape.CIRCLE_ROTATE) {
            lastCoverFrameAt = now
            return
        }
        if (lastCoverFrameAt != 0L && playing) {
            val dt = (now - lastCoverFrameAt) / 1000f
            coverRotation = (coverRotation + (360f / COVER_ROTATION_SECONDS) * dt) % 360f
        }
        lastCoverFrameAt = now
    }

    /** 封面停靠进度：0=引导卡居中（大封面），1=角落停靠（from→target easeOutCubic 过渡，可双向）。 */
    private fun coverDockProgress(now: Long): Float {
        // SuperLyric 单句模式：封面常驻大封面，永不停靠角落。
        if (singleLineMode && !panelMode) return 0f
        val base = if (dockAnimAt == 0L) {
            dockTarget
        } else {
            lerp(dockFrom, dockTarget, easeOutCubic(((now - dockAnimAt) / DOCK_MS).coerceIn(0f, 1f)))
        }
        // 面板展开同步把停靠进度拉向 0（大封面）：封面回位/歌名歌手淡出/角落时钟淡出与面板开合一起进行，不滞后。
        return base * (1f - panelProgress)
    }

    /** 文字（歌名/时间）淡入：停靠到位后才开始（"封面动画完毕后"）；未停靠则隐藏。 */
    private fun clockFadeAlpha(now: Long): Float {
        if (dockCompletedAt == 0L) return 0f
        val t = ((now - dockCompletedAt) / CLOCK_FADE_MS).coerceIn(0f, 1f)
        return smoothstep(t)
    }

    private fun currentClockText(): String {
        val minute = System.currentTimeMillis() / 60000L
        if (minute != clockMinute) {
            clockMinute = minute
            clockText = clockFormat.format(Date())
        }
        return clockText
    }

    private fun introCoverSizePx(): Float {
        val cw = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val ch = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        return (cw * INTRO_COVER_WIDTH_FRAC)
            .coerceIn(dp(INTRO_COVER_MIN_DP), dp(INTRO_COVER_MAX_DP))
            .coerceAtMost(ch * 0.42f)
    }

    private fun dockCoverSizePx(): Float = textSizePx * DOCK_COVER_EM

    private fun layoutWidth(layout: StaticLayout): Float {
        var w = 0f
        for (i in 0 until layout.lineCount) w = max(w, layout.getLineWidth(i))
        return w
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun buildTypeface(cfg: RearConfig): Typeface = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(Typeface.DEFAULT, cfg.fontWeight, cfg.italic)
        cfg.bold && cfg.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        cfg.bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        cfg.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        else -> Typeface.DEFAULT
    }

    private enum class LineAlign { LEFT, CENTER, RIGHT }

    private class Token(
        val text: String,
        var x: Float = 0f,
        var baseline: Float = 0f,
        var rowTop: Float = 0f,
        var rowBottom: Float = 0f,
        var width: Float = 0f,
        var begin: Long = 0L,
        var end: Long = 0L,
        /** 该 token 绘制字号（中文=中文字号，英文=中文+[LATIN_EXTRA_PX]）。 */
        var size: Float = 0f,
        /** SuperLyric「随机升起」进场：本 token 相对句首的起始延迟（ms），随机非线性。 */
        var riseDelay: Long = 0L,
    )

    private class LineModel(
        val tokens: List<Token>,
        val mainHeight: Float,
        val rowCount: Int,
    )

    /** 副行：自带逐字时间走 token 渐变进度，否则 StaticLayout 纯渐显。 */
    private class SecondaryEntry(
        val model: LineModel?,
        val layout: StaticLayout?,
    ) {
        val height: Float
            get() = model?.mainHeight ?: (layout?.height?.toFloat() ?: 0f)
    }

    private data class LayoutKey(
        val songId: Int,
        val width: Int,
        val padLeft: Int,
        val padRight: Int,
        val bold: Boolean,
        val italic: Boolean,
        val fontWeight: Int,
        val showSecondary: Boolean,
        val showTranslation: Boolean,
        val showRoma: Boolean,
        val relativeHighlight: Boolean,
        val simulateWordTiming: Boolean,
        val lyricTextSize: Int,
        val playerPackage: String?,
    )
}
