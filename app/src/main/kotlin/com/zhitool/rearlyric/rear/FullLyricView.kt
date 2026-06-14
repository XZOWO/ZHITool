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
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.zhitool.rearlyric.lyric.CoverPosition
import com.zhitool.rearlyric.lyric.CoverShape
import com.zhitool.rearlyric.lyric.LyricColors
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 固定字号：按一行排满九个汉字计算。 */
private const val CHARS_PER_LINE = 9
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
/** 旋转封面每圈用时（秒），与单句模式一致。 */
private const val COVER_ROTATION_SECONDS = 16f
/** 渐变高亮：前沿羽化宽度（相对字号）。 */
private const val GRADIENT_SOFT_EM = 0.9f
/** 渐变高亮 alpha 蒙版色（仅 alpha 有意义：不透明 → 透明）。 */
private val HIGHLIGHT_MASK_COLORS = intArrayOf(-0x1, 0x00FFFFFF)

/** Apple Music：歌词自带逐句居左/居右（对唱），直接采用。 */
private const val APPLE_MUSIC_PKG = "com.apple.android.music"

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
    /** 角落封面当前是否已停靠 + 命中区（视图坐标，含封面与左侧文字区，点击切换歌名/时间）。 */
    private var coverDocked = false
    private val dockedCoverHitRect = RectF()

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
        // 切歌：接下来 SONG_SWITCH_HOLD_MS 内强制新歌按"首句前"渲染（默认从头、显示大封面）。
        if (songChanged && this.song != null) {
            songSwitchHoldUntil = SystemClock.elapsedRealtime() + SONG_SWITCH_HOLD_MS
        }
        // 换歌：先快照当前画面，onDraw 让它上滑淡出、新歌内容从下方顶上来。
        if (songChanged && this.song != null && song != null && width > 0 && height > 0) {
            captureSongSwitchSnapshot()
        }
        // 封面对象变化（换歌/重解码）时旋转角归零，与单句模式一致。
        if (this.coverBitmap !== coverBitmap) {
            this.coverBitmap = coverBitmap
            coverRotation = 0f
            lastCoverFrameAt = 0L
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
        this.positionMs = if (SystemClock.elapsedRealtime() < songSwitchHoldUntil) 0L else positionMs
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
            playerPackage = playerPackage,
        )
        if (key != layoutKey) {
            layoutKey = key
            rebuildLayouts()
        }
        refreshHighlightShader()
        updateScroll(snap = songChanged)
        invalidate()
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
        draw(Canvas(bmp))
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
            postInvalidateOnAnimation()
            return
        }
        if (snapshot != null) {
            snapshot.recycle()
            songSwitchBitmap = null
        }
        renderLyrics(canvas, now)
    }

    private fun renderLyrics(canvas: Canvas, now: Long) {
        if (models.isEmpty()) {
            // 无歌词：全量模式退化为"封面 + 歌名 + 歌手"居中展示（同歌曲信息模式）。
            drawNoLyricCard(canvas, now)
            return
        }
        val target = targetScrollFor(currentIndex, now)
        val scroll: Float
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
        lastScroll = scroll

        // 歌词上下不设安全区限制：以整屏（视图全高）为中心，允许滑出屏幕（进出场动效本就来自屏幕外）。
        val viewCenter = height / 2f
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

        val first = (currentIndex - DRAW_RADIUS).coerceAtLeast(0)
        val last = (currentIndex + DRAW_RADIUS).coerceAtMost(models.lastIndex)
        for (i in first..last) {
            val top = topOf(i, now)
            val blockHeight = blockHeightOf(i, now)
            val norm = abs(top + blockHeight / 2f - scroll) / fadeRange
            // 当前句与抢唱副句都在演唱：全强度、不渐隐模糊、始终绘制（超长句句内滚动时块心可能远离 scroll）。
            val isActive = i == currentIndex || i == overlapIndex
            if (!isActive && norm >= 1f) continue
            val alphaF = if (isActive) 1f else (1f - norm).pow(FADE_EXPONENT)
            val blur = if (isActive) null else blurFor(norm)
            drawLineBlock(canvas, i, top, blockHeight, alphaF, blur, revealFactorAt(i, now), norm, lineScale(i, now))
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

        val revealAnimating = (revealIndex >= 0 && now - revealStartedAt < REVEAL_MS) ||
            (collapseIndex >= 0 && now - collapseStartedAt < REVEAL_MS)
        val zoomAnimating = now - zoomStartedAt < ZOOM_MS.toLong()
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
                (coverDocked && dockNameTarget >= 0.5f)
        )
        if (scrollAnimating || revealAnimating || zoomAnimating || overlapIndex >= 0 || coverAnimating) {
            postInvalidateOnAnimation()
        }
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
        textSizePx = if (sample > 0f) 100f * contentWidth / sample else contentWidth / CHARS_PER_LINE.toFloat()
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
        introNamePaint.textSize = textSizePx * INTRO_NAME_EM
        introArtistPaint.textSize = textSizePx * INTRO_ARTIST_EM
        val coverSize = introCoverSizePx()
        val textWidth = (contentWidth - coverSize - textSizePx * INTRO_GAP_EM)
            .toInt().coerceAtLeast(1)
        introNameLayout = StaticLayout.Builder.obtain(name, 0, name.length, introNamePaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(2)
            .build()
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
    ): LineModel {
        val rowHeight = textPaint.fontSpacing * LINE_SPACING_MULT
        val wordList = words.orEmpty()
        val timed = wordList.isNotEmpty()
        val pieces: List<Token> = if (timed) {
            wordList.flatMap { w -> wordTokens(textPaint, w.text.orEmpty(), w.begin, w.end, splitCjkWords) }
        } else {
            splitPlain(lineText).map { Token(text = it) }
        }
        if (pieces.isEmpty()) return LineModel(emptyList(), rowHeight, 1)

        pieces.forEach { it.width = textPaint.measureText(it.text) }

        val rows = ArrayList<MutableList<Token>>()
        var row = mutableListOf<Token>()
        var rowWidth = 0f
        for (tok in pieces) {
            if (row.isNotEmpty() && rowWidth + tok.width > contentWidth) {
                rows += row
                row = mutableListOf()
                rowWidth = 0f
            }
            row += tok
            rowWidth += tok.width
        }
        if (row.isNotEmpty()) rows += row

        val ascent = -textPaint.ascent()
        rows.forEachIndexed { r, rowTokens ->
            val rawWidth = rowTokens.sumOf { it.width.toDouble() }.toFloat()
            val lastTok = rowTokens.last()
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
        val tokens = rows.flatten()
        if (!timed) {
            val total = tokens.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
            val dur = (lineEnd - lineBegin).coerceAtLeast(1L)
            var cum = 0f
            for (tok in tokens) {
                tok.begin = lineBegin + (dur * (cum / total)).toLong()
                cum += tok.width
                tok.end = lineBegin + (dur * (cum / total)).toLong()
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
        val wantDock = currentIndex >= 0
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
                canvas.drawText(tok.text, tok.x, tok.baseline + sinkPx, paint)
            }
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
        applyHighlight(textPaint, baseAlpha)
        for (tok in tokens) canvas.drawText(tok.text, tok.x, tok.baseline, textPaint)
        textPaint.shader = null
        // 默认白字模式高亮本就是白，不再叠白（否则会抹掉已读透明度）；其它配色才需要"色→白"过渡。
        if (config.textColorMode != TextColorMode.DEFAULT) {
            val whiteBlend = recede.coerceIn(0f, 1f).pow(WHITE_FADE_EXPONENT)
            textPaint.color = Color.WHITE
            textPaint.alpha = (baseAlpha * whiteBlend).roundToInt().coerceIn(0, 255)
            for (tok in tokens) canvas.drawText(tok.text, tok.x, tok.baseline, textPaint)
        }
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
        for (tok in tokens) {
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
        val centerY = contentTop + contentHeight / 2f

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
            drawCoverBitmap(canvas, bmp, coverCx, centerY, cover, rotationDeg)
            drawIntroText(canvas, textLeft, centerY, 1f)
            val scaleAnimating = coverScaleStartedAt != 0L && now - coverScaleStartedAt < DOCK_SCALE_MS.toLong()
            if ((config.coverShape == CoverShape.CIRCLE_ROTATE && playing) || scaleAnimating) {
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

        // 命中区（点封面或左侧文字区切换歌名/时间）：停靠超过一半即可点，覆盖封面 + 左侧文字带。
        coverDocked = p > 0.6f
        if (coverDocked) {
            val pad = dp(12f)
            dockedCoverHitRect.set(
                contentLeft,
                coverCy - coverSize / 2f - pad,
                coverCx + coverSize / 2f + pad,
                coverCy + coverSize / 2f + pad,
            )
        }

        drawCoverBitmap(canvas, bmp, coverCx, coverCy, coverSize, rotationDeg)

        val introAlpha = (1f - p).coerceIn(0f, 1f)
        if (introAlpha > 0.01f) {
            drawIntroText(canvas, textLeft, introCoverCy, introAlpha)
            // 圆点跟随首句对齐、位于第一句歌词上方（首句以整屏为中心，故用 height/2 定位）。
            val firstLineTop = height / 2f - lastScroll
            val dotsY = firstLineTop - textSizePx * 0.55f
            drawIntroDots(canvas, contentLeft, contentWidth, dotsY, introAlpha)
        }

        val textAlpha = clockFadeAlpha(now)
        if (textAlpha > 0.01f) {
            // 歌名↔时间：自动模式歌名显示 NAME_SHOW_MS 后切时间；点击切换并锁定（手动覆盖）。
            val showName = when (manualNameOverride) {
                1 -> true
                0 -> false
                else -> now < nameRevealAt + NAME_SHOW_MS
            }
            retargetDockName(showName, now)
            val nameFactor = currentDockNameFactor(now)
            // 文字区在封面左侧：右边界=封面左缘-间距，左边界=内容左缘。
            val rightEdge = coverCx - coverSize / 2f - dp(CLOCK_GAP_DP)
            drawDockText(canvas, contentLeft, rightEdge, coverCy, textAlpha, nameFactor, now)
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

    private fun nameArtistText(): String {
        val text = listOf(song?.name.orEmpty(), song?.artist.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        return text.ifBlank { "未知歌曲" }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val inHit = coverDocked && dockedCoverHitRect.contains(event.x, event.y)
        when (event.action) {
            // 必须消费 DOWN 才能收到 UP（非 clickable 的 View 默认 DOWN 返回 false 就收不到后续事件）。
            android.view.MotionEvent.ACTION_DOWN -> if (inHit) return true
            android.view.MotionEvent.ACTION_UP -> if (inHit) {
                // 点封面/时间区：在歌名与时间之间切换并锁定（手动覆盖，不再自动回切）。
                val showingName = dockNameTarget >= 0.5f
                if (showingName) {
                    manualNameOverride = 0
                } else {
                    manualNameOverride = 1
                    nameRevealAt = SystemClock.elapsedRealtime() // 跑马灯从居右重新起步
                }
                performClick()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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

    /** 封面停靠进度：0=引导卡居中，1=角落停靠（from→target easeOutCubic 过渡，可双向）。 */
    private fun coverDockProgress(now: Long): Float {
        if (dockAnimAt == 0L) return dockTarget
        val t = ((now - dockAnimAt) / DOCK_MS).coerceIn(0f, 1f)
        return lerp(dockFrom, dockTarget, easeOutCubic(t))
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
        val playerPackage: String?,
    )
}
