package com.zhitool.rearlyric.rear

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.zhitool.rearlyric.lyric.StaggerConfig
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

// ---- 排版 ----
/** 字号按一行 6 个汉字占满换行宽自适应，再乘用户字体百分比；最多 3 行。 */
private const val ST_CHARS_PER_LINE = 6
private const val ST_MAX_ROWS = 3
private const val ST_MAX_BLOCK_FRAC = 0.48f
/** 行高系数与换行宽占比（都相对基准字号/内容宽，预留错落抖动的余量）。 */
private const val ST_ROW_HEIGHT = 1.46f
private const val ST_WRAP_FRAC = 0.92f

// ---- 每字随机（错位交替的"错位"）----
/** 随机大小（基准的 0.84~1.16 倍）。 */
private const val ST_SIZE_MIN = 0.84f
private const val ST_SIZE_VAR = 0.32f
/** 左右随机错位（±0.12em）与字间随机空隙（0~0.14em）。 */
private const val ST_DX_EM = 0.24f
private const val ST_GAP_EM = 0.14f
/** 上下交替错落：相邻字一上一下（交替），幅度 0.08~0.30em 随机。 */
private const val ST_DY_MIN_EM = 0.08f
private const val ST_DY_VAR_EM = 0.22f
/** 随机左右倾斜（度）：每字随机方向 1~7° 微倾。 */
private const val ST_TILT_MIN_DEG = 1f
private const val ST_TILT_VAR_DEG = 6f
/** 随机前后深度层：远层（0）更小更暗，近层（1）大而亮；绘制按远→近排序产生遮挡层次。 */
private const val ST_DEPTH_SCALE_MIN = 0.85f
private const val ST_DEPTH_SCALE_VAR = 0.30f
private const val ST_DEPTH_ALPHA_MIN = 0.58f
private const val ST_DEPTH_ALPHA_VAR = 0.42f

// ---- 出字（从下方升起渐显）----
private const val ST_RISE_MS = 460f
private const val ST_RISE_DIST_EM = 0.95f
/** 无逐字时间：整句一起进入，每字只带少量随机抖动（总体看是同时出现）。 */
private const val ST_UNTIMED_JITTER_MS = 240L
private const val ST_UNTIMED_BASE_DELAY_MS = 100L

// ---- 漂浮（出字后像漂在太空中的椭圆游走）----
private const val ST_FLOAT_AMP_X_EM = 0.05f
private const val ST_FLOAT_AMP_Y_EM = 0.075f
private const val ST_FLOAT_PERIOD_MIN_MS = 3400f
private const val ST_FLOAT_PERIOD_VAR_MS = 3200f

// ---- 切句（后退感）----
/** 一代句整体后缩（缩小 + 压暗变透明 + 飞向随机远点）时长；新句整体 1.10→1 缩入。 */
private const val ST_RECEDE_MS = 780f
private const val ST_ENTER_MS = 700f
private const val ST_ENTER_SCALE_FROM = 1.10f
/**
 * 屏上保留 3 句：当前句 + 两代远景句。一代（上一句）：随机远点、随机缩放、alpha 0.38；
 * 二代（上上句）：原地再缩 0.62、alpha 0.20、更糊；三代整句淡出消失。
 */
private const val ST_GEN1_ALPHA = 0.38f
private const val ST_GEN2_ALPHA = 0.20f
private const val ST_GEN2_SCALE_MULT = 0.62f
/** 一代句驻留远处的随机缩放范围；三代淡出时长。 */
private const val ST_PREV_SCALE_MIN = 0.34f
private const val ST_PREV_SCALE_VAR = 0.18f
private const val ST_FADEOUT_MS = 640f
/** 当前句竖直中心（相对内容高）。上一句无固定槽位——随机飞到背景任何位置。 */
private const val ST_CURRENT_CY = 0.56f
/** 随机远点的采样范围（相对内容宽/高）与离当前句槽位的最小距离（相对内容高）。 */
private const val ST_PARK_X_MIN = 0.12f
private const val ST_PARK_X_MAX = 0.88f
private const val ST_PARK_Y_MIN = 0.08f
private const val ST_PARK_Y_MAX = 0.86f
private const val ST_PARK_MIN_DIST = 0.26f
/**
 * 已读歌词与星空同向：驻留的旧句也是星空的一部分——每次后退脉冲随星星向本次消失点
 * 再收敛一步（保留系数：新位置 = vp + (旧位置-vp)×该值，越小收敛越多；与星星近层
 * 收敛幅度同量级）。
 */
private const val ST_BG_KEEP = 0.55f
/** 远处句子的轻微模糊（相对基准字号；硬件层 maskFilter 需 API28+，低版本自动跳过）。 */
private const val ST_PREV_BLUR_EM = 0.09f

    // ---- 封面页（整页长按打开、点封面关闭）----
/** 面板全开时歌词舞台的缩小/压暗/向中心收拢幅度（同"后退"语言）。 */
private const val ST_PANEL_SHRINK = 0.55f
private const val ST_PANEL_DIM = 0.85f
private const val ST_PANEL_PULL = 0.35f

/** 发光：与默认样式同一效果——BlurMaskFilter 半径固定 0.22em，音乐能量只调发光透明度。 */
private const val ST_GLOW_EM = 0.22f

// ---- 右上角时钟（位置对齐默认模式的停靠时钟：贴角 10dp、字号=停靠封面的 0.62）----
private const val ST_CLOCK_MARGIN_DP = 10f
private const val ST_CLOCK_BOX_DP = 20.5f
private const val ST_CLOCK_TEXT_DP = 12.8f

/**
 * 「错位交替」歌词渲染器（纯净模式，只画歌词与右上角时钟，星空背景在下层 Compose）：
 * 屏上保留 3 句（当前 + 两代远景）；每个字/单词随机左右错位、随机大小、上下交替错落、
 * 随机左右微倾、随机前后深度层，从下方升起渐显（有逐字时间按逐字顺序由音乐进度驱动，
 * 无逐字时间整句齐入只带少量随机抖动）；出字后椭圆游走漂浮 + 同款鼓点发光。
 * 切句时上一句向**随机远点**后缩压暗变透明驻留，上上句原地再退一档，三代淡出；
 * 同时经 [onRecedePulse] 通知星空以同一消失点做后退脉冲——歌词动星星才动。
 * [setPanelProgress]（双击开封面页）让整个歌词舞台以同样的后退语言缩小压暗让位。
 */
internal class StaggerLyricView(context: Context) : View(context) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, 700, false)
        } else {
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(110, 0, 0, 0))
    }
    private val blurCache = HashMap<Int, BlurMaskFilter>()

    /** 切句后退脉冲：view 内像素坐标的消失点 + 时长，由宿主转成全屏坐标喂给星空。 */
    var onRecedePulse: ((vpX: Float, vpY: Float, durMs: Long) -> Unit)? = null

    // ---- 歌词发光（与默认样式同链路：root 内录能量 → 发光透明度）----
    private var glowEnabled = false
    private var glowPerc = 0f
    private var glowHarm = 0f

    fun setAudioEnergy(perc: Float, harm: Float, on: Boolean) {
        glowEnabled = on
        glowPerc = if (on) perc.coerceIn(0f, 1f) else 0f
        glowHarm = if (on) harm.coerceIn(0f, 1f) else 0f
    }

    /** 发光混合能量（低音为主、非低音为辅），与 FullLyricView 同配比。 */
    private fun rhythmGlow(): Float = (glowPerc * 0.7f + glowHarm * 0.3f).coerceIn(0f, 1f)

    // ---- 封面页开合（0=歌词正常，1=面板全开：舞台后退让位）----
    private var panelProgress = 0f

    fun setPanelProgress(p: Float) {
        val v = p.coerceIn(0f, 1f)
        if (v != panelProgress) {
            panelProgress = v
            invalidate()
        }
    }

    // ---- 绑定状态 ----
    private var song: Song? = null
    private var lines: List<RichLyricLine> = emptyList()
    private var positionMs = 0L
    private var bindWallAt = 0L
    private var config: StaggerConfig = StaggerConfig()
    private var playing = false
    private var singleLine = false
    private var songKey: String? = null
    private var layoutSig: String? = null

    fun bind(song: Song?, positionMs: Long, config: StaggerConfig, playing: Boolean, singleLine: Boolean) {
        this.config = config
        this.playing = playing
        this.positionMs = positionMs
        bindWallAt = SystemClock.elapsedRealtime()
        if (singleLine != this.singleLine) {
            this.singleLine = singleLine
            clearBlocks()
        }
        if (song !== this.song) {
            this.song = song
            lines = song?.lyrics.orEmpty().filter { !it.text.isNullOrBlank() }.sortedBy { it.begin }
            // 换歌（按逻辑歌曲判定）清空舞台；同一首补下发歌词只刷新 lines，块按句键自然续用/重建。
            val key = song?.let { "${it.name}|${it.artist}" }
            if (key != songKey) {
                songKey = key
                clearBlocks()
            }
        }
        invalidate()
    }

    private fun clearBlocks() {
        current = null
        prev1 = null
        prev2 = null
        fading = null
    }

    // ---- 四个槽位（屏上保留 3 句）：当前句 / 一代（上一句，随机远点）/ 二代 / 三代（淡出中）----
    private var current: Block? = null
    private var prev1: Block? = null
    private var prev2: Block? = null
    private var fading: Block? = null
    /** 最近一次切句的墙钟（同时驱动所有代际过渡）。 */
    private var switchAt = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = SystemClock.elapsedRealtime()
        // SuperLyric 进度来自系统会话（~500ms 一拍），按经过时间外推；词幕源 bind 每帧刷新，增量可忽略。
        val effPos = if (playing) positionMs + (now - bindWallAt).coerceAtLeast(0L) else positionMs

        // 尺寸/边距/字号百分比变化：整体重建（罕见事件，直接清场重来）。
        val sig = "$width|$height|$paddingLeft|$paddingTop|$paddingRight|$paddingBottom|${config.textSizePercent}"
        if (sig != layoutSig) {
            layoutSig = sig
            clearBlocks()
        }

        resolveCurrentLine(effPos, now)

        val contentW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentH = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val cx = paddingLeft + contentW / 2f
        val currentCy = paddingTop + contentH * ST_CURRENT_CY
        // 封面页开合：整个舞台向内容中心收拢缩小压暗（同"后退"语言，由面板进度驱动）。
        val stageCx = paddingLeft + contentW / 2f
        val stageCy = paddingTop + contentH / 2f
        val p = panelProgress
        val stageScale = 1f - ST_PANEL_SHRINK * p
        val stageAlpha = 1f - ST_PANEL_DIM * p

        fun stagedX(x: Float) = lerp(x, stageCx, ST_PANEL_PULL * p)
        fun stagedY(y: Float) = lerp(y, stageCy, ST_PANEL_PULL * p)

        // 本次后退脉冲的位移进度：三代句的移动也用它（与星星/一代句同拍同向——都是星空的一部分）。
        val recedeE = easeOutCubic(((now - switchAt) / ST_RECEDE_MS).coerceIn(0f, 1f))

        // 三代句：随本次脉冲继续向消失点收敛，同时从二代状态整句淡出消失，播完丢弃。
        fading?.let { b ->
            val t = ((now - switchAt) / ST_FADEOUT_MS).coerceIn(0f, 1f)
            if (t >= 1f) {
                fading = null
            } else {
                val e = easeOutCubic(t)
                val gen2Scale = b.parkScale * ST_GEN2_SCALE_MULT
                drawBlock(
                    canvas, b,
                    cx = stagedX(lerp(b.fromX, b.parkX, recedeE)),
                    cy = stagedY(lerp(b.fromY, b.parkY, recedeE)),
                    scale = lerp(gen2Scale, gen2Scale * 0.6f, e) * stageScale,
                    blockAlpha = lerp(ST_GEN2_ALPHA, 0f, e) * stageAlpha,
                    now = now, effPos = effPos,
                    glowOn = false, forceAppear = true,
                    blurRadius = b.base * ST_PREV_BLUR_EM * 1.6f,
                )
            }
        }

        // 二代句（上上句）：随本次脉冲向消失点收敛一步 + 再退一档（再缩 + 再暗 + 更糊），随后驻留漂浮。
        prev2?.let { b ->
            drawBlock(
                canvas, b,
                cx = stagedX(lerp(b.fromX, b.parkX, recedeE)),
                cy = stagedY(lerp(b.fromY, b.parkY, recedeE)),
                scale = lerp(b.parkScale, b.parkScale * ST_GEN2_SCALE_MULT, recedeE) * stageScale,
                blockAlpha = lerp(ST_GEN1_ALPHA, ST_GEN2_ALPHA, recedeE) * stageAlpha,
                now = now, effPos = effPos,
                glowOn = false, forceAppear = true,
                blurRadius = b.base * ST_PREV_BLUR_EM * (1f + 0.6f * recedeE),
            )
        }

        // 一代句（上一句）：从当前槽位向随机远点后缩（缩小 + 压暗变透明），随后驻留漂浮。
        prev1?.let { b ->
            drawBlock(
                canvas, b,
                cx = stagedX(lerp(b.fromX, b.parkX, recedeE)),
                cy = stagedY(lerp(b.fromY, b.parkY, recedeE)),
                scale = lerp(1f, b.parkScale, recedeE) * stageScale,
                blockAlpha = lerp(1f, ST_GEN1_ALPHA, recedeE) * stageAlpha,
                now = now, effPos = effPos,
                glowOn = false, forceAppear = true,
                blurRadius = b.base * ST_PREV_BLUR_EM * recedeE,
            )
        }

        // 当前句：整体从 1.10 缩到 1（从身后来到面前），字按各自时序升起渐显 + 漂浮 + 发光。
        current?.let { b ->
            val enter = easeOutCubic(((now - b.bornAt) / ST_ENTER_MS).coerceIn(0f, 1f))
            drawBlock(
                canvas, b,
                cx = stagedX(cx),
                cy = stagedY(currentCy),
                scale = lerp(ST_ENTER_SCALE_FROM, 1f, enter) * stageScale,
                blockAlpha = stageAlpha,
                now = now, effPos = effPos,
                glowOn = true, forceAppear = false,
                blurRadius = 0f,
            )
        }

        // 右上角时钟（可开关；开封面页时随舞台一起淡出，与默认模式的停靠时钟行为一致）。
        if (config.showClock && stageAlpha > 0.01f) {
            drawClock(canvas, stageAlpha)
        }

        // 漂浮/出字/后缩都是持续动画：只要有块在台上（或在播放等出字）就自驱动逐帧重绘。
        if (current != null || prev1 != null || prev2 != null || fading != null || playing) {
            postInvalidateOnAnimation()
        }
    }

    /** 解析当前句；句键变化即切句：一代句飞向随机远点，同时向星空发同消失点的后退脉冲。 */
    private fun resolveCurrentLine(effPos: Long, now: Long) {
        val line: RichLyricLine? = if (singleLine) {
            lines.firstOrNull()
        } else {
            var idx = -1
            for (i in lines.indices) {
                if (lines[i].begin <= effPos) idx = i else break
            }
            lines.getOrNull(idx)
        }
        val key = line?.let { "${it.begin}|${it.text}" }
        if (key == current?.lineKey) return

        val outgoing = current
        fading = prev2
        prev2 = prev1
        prev1 = outgoing
        if (outgoing != null) {
            assignParkTarget(outgoing)
            // 已读歌词与星空同向：驻留中的旧句也随本次脉冲向同一消失点收敛一步（都是星空的一部分）。
            val vpX = outgoing.parkX
            val vpY = outgoing.parkY
            prev2?.driftToward(vpX, vpY)
            fading?.driftToward(vpX, vpY)
            // 星空后退脉冲：消失点=这句要退去的远点，星星与歌词同拍收敛——歌词动星星才动。
            onRecedePulse?.invoke(vpX, vpY, ST_RECEDE_MS.toLong())
        } else {
            prev2?.stay()
            fading?.stay()
        }
        current = if (line != null) buildBlock(line, key!!, now) else null
        switchAt = now
    }

    /** 给退场句挑随机远点（背景任何位置，但避开当前句槽位附近）与随机驻留缩放。 */
    private fun assignParkTarget(b: Block) {
        val contentW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val contentH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val slotX = paddingLeft + contentW / 2f
        val slotY = paddingTop + contentH * ST_CURRENT_CY
        val minDist = contentH * ST_PARK_MIN_DIST
        val rng = Random(SystemClock.elapsedRealtime().toInt() xor b.lineKey.hashCode())
        var px = slotX
        var py = slotY
        // 拒绝采样：避开当前句槽位附近；8 次都没躲开就用最后一个（有缩小压暗，重叠也可接受）。
        for (i in 0 until 8) {
            px = paddingLeft + contentW * (ST_PARK_X_MIN + rng.nextFloat() * (ST_PARK_X_MAX - ST_PARK_X_MIN))
            py = paddingTop + contentH * (ST_PARK_Y_MIN + rng.nextFloat() * (ST_PARK_Y_MAX - ST_PARK_Y_MIN))
            val dx = px - slotX
            val dy = py - slotY
            if (dx * dx + dy * dy >= minDist * minDist) break
        }
        b.fromX = slotX
        b.fromY = slotY
        b.parkX = px
        b.parkY = py
        b.parkScale = ST_PREV_SCALE_MIN + rng.nextFloat() * ST_PREV_SCALE_VAR
    }

    // ---- 块与 token ----

    private class STok(
        val text: String,
        /** 有逐字时间：音乐时间轴上的出字时刻（词序即逐字顺序）。 */
        val appearAt: Long,
        /** 无逐字时间：相对块登台的墙钟延迟（整句齐入，只有少量随机抖动）。 */
        val appearDelay: Long,
        /** 前后深度层 0（远）..1（近）：决定遮挡次序与明暗。 */
        val depth: Float,
        val depthAlpha: Float,
        /** 随机左右倾斜（度，带符号）。 */
        val tiltDeg: Float,
        /** 漂浮参数（角频率 rad/s、相位、振幅系数——相对自身字号）。 */
        val floatW1: Float,
        val floatW2: Float,
        val floatP1: Float,
        val floatP2: Float,
        val ampXF: Float,
        val ampYF: Float,
        var size: Float = 0f,
        var width: Float = 0f,
        var gap: Float = 0f,
        var x: Float = 0f,
        var baseline: Float = 0f,
        var dy: Float = 0f,
    )

    private class Block(
        val lineKey: String,
        /** 布局序（换行用）与绘制序（按深度远→近，近层字盖在远层上）。 */
        val tokens: List<STok>,
        val drawOrder: List<STok>,
        val width: Float,
        val height: Float,
        /** 基准字号（发光半径/升起距离/模糊半径的量纲）。 */
        val base: Float,
        /** 是否带逐字时间（决定出字驱动源：音乐进度 vs 墙钟）。 */
        val timed: Boolean,
        val bornAt: Long,
    ) {
        /** 退场随机远点（切句时赋值）：驻留位置（view 内像素）与驻留缩放。 */
        var parkX = 0f
        var parkY = 0f
        var parkScale = ST_PREV_SCALE_MIN
        /** 本次代际过渡的位置起点（切句时=上一个驻留点/当前句槽位）。 */
        var fromX = 0f
        var fromY = 0f

        /** 星空同向：旧句作为星空的一部分，随本次后退脉冲向消失点再收敛一步（方向与星星一致）。 */
        fun driftToward(vpX: Float, vpY: Float) {
            fromX = parkX
            fromY = parkY
            parkX = vpX + (parkX - vpX) * ST_BG_KEEP
            parkY = vpY + (parkY - vpY) * ST_BG_KEEP
        }

        /** 原地驻留（无出场句的边界情形）：位置不动。 */
        fun stay() {
            fromX = parkX
            fromY = parkY
        }
    }

    /** 整句排版 + 每字随机属性（随机数用句键做种子：重建结果稳定，不会每帧抖动）。 */
    private fun buildBlock(line: RichLyricLine, lineKey: String, now: Long): Block? {
        val contentW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val contentH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val rng = Random(lineKey.hashCode())

        val wordList = line.words.orEmpty()
        val timed = wordList.isNotEmpty()
        val pieces: List<Pair<String, Long>> = if (timed) {
            wordList.flatMap { splitWord(it) }
        } else {
            splitPlain(line.text.orEmpty()).map { it to 0L }
        }
        if (pieces.isEmpty()) return null

        val tokens = pieces.map { (text, begin) ->
            val depth = rng.nextFloat()
            val tiltSign = if (rng.nextBoolean()) 1f else -1f
            STok(
                text = text,
                appearAt = max(begin, line.begin),
                // 无逐字：整句齐入，只带少量随机抖动（总体看是一起出现的）。
                appearDelay = ST_UNTIMED_BASE_DELAY_MS + rng.nextLong(ST_UNTIMED_JITTER_MS),
                depth = depth,
                depthAlpha = ST_DEPTH_ALPHA_MIN + ST_DEPTH_ALPHA_VAR * depth,
                tiltDeg = tiltSign * (ST_TILT_MIN_DEG + rng.nextFloat() * ST_TILT_VAR_DEG),
                floatW1 = twoPi / periodSec(rng),
                floatW2 = twoPi / periodSec(rng),
                floatP1 = rng.nextFloat() * twoPi,
                floatP2 = rng.nextFloat() * twoPi,
                ampXF = ST_FLOAT_AMP_X_EM * (0.6f + rng.nextFloat() * 0.8f),
                ampYF = ST_FLOAT_AMP_Y_EM * (0.6f + rng.nextFloat() * 0.8f),
            )
        }
        // 每字尺寸系数 = 随机大小 × 深度缩放（远层更小）；字间空隙与左右错位系数一并预生成。
        val sizeF = FloatArray(tokens.size)
        val gapF = FloatArray(tokens.size)
        val dxF = FloatArray(tokens.size)
        val dyF = FloatArray(tokens.size)
        tokens.forEachIndexed { i, tok ->
            sizeF[i] = (ST_SIZE_MIN + rng.nextFloat() * ST_SIZE_VAR) *
                (ST_DEPTH_SCALE_MIN + ST_DEPTH_SCALE_VAR * tok.depth)
            gapF[i] = rng.nextFloat() * ST_GAP_EM
            dxF[i] = (rng.nextFloat() - 0.5f) * ST_DX_EM
            // 上下交替错落：相邻字符号交替（交替），幅度随机（错位）。
            val sign = if (i % 2 == 0) -1f else 1f
            dyF[i] = sign * (ST_DY_MIN_EM + rng.nextFloat() * ST_DY_VAR_EM)
        }

        // 基准字号：一行 6 个汉字占满换行宽 × 用户字体百分比起步，
        // 随后满足行数/块高/最宽单词三约束往下缩。
        val wrapW = contentW * ST_WRAP_FRAC
        paint.textSize = 100f
        val unit = (paint.measureText("汉") * ST_CHARS_PER_LINE).coerceAtLeast(1f)
        var base = 100f * wrapW / unit * (config.textSizePercent.coerceIn(40, 200) / 100f)
        var rows: List<List<Int>>
        while (true) {
            tokens.forEachIndexed { i, tok ->
                tok.size = base * sizeF[i]
                paint.textSize = tok.size
                tok.width = paint.measureText(tok.text)
                tok.gap = base * gapF[i]
            }
            rows = wrapRows(tokens, wrapW)
            val widest = tokens.maxOf { it.width }
            val blockH = rows.size * base * ST_ROW_HEIGHT
            if (base <= 6f ||
                (rows.size <= ST_MAX_ROWS && blockH <= contentH * ST_MAX_BLOCK_FRAC && widest <= wrapW)
            ) {
                break
            }
            base *= 0.94f
        }

        val rowH = base * ST_ROW_HEIGHT
        rows.forEachIndexed { r, rowIdx ->
            val rowW = rowIdx.sumOf { (tokens[it].width + tokens[it].gap).toDouble() }.toFloat() -
                tokens[rowIdx.last()].gap
            var x = ((contentW - rowW) / 2f).coerceAtLeast(0f)
            val baseline = r * rowH + base * 1.05f
            for (i in rowIdx) {
                val tok = tokens[i]
                tok.x = x + dxF[i] * base
                tok.baseline = baseline
                tok.dy = dyF[i] * base
                x += tok.width + tok.gap
            }
        }

        return Block(
            lineKey = lineKey,
            tokens = tokens,
            drawOrder = tokens.sortedBy { it.depth },
            width = contentW,
            height = rows.size * rowH,
            base = base,
            timed = timed,
            bornAt = now,
        )
    }

    /** 贪心换行（含随机字间隙），返回每行的 token 下标；单 token 超宽则独占一行。 */
    private fun wrapRows(tokens: List<STok>, wrapW: Float): List<List<Int>> {
        val rows = ArrayList<MutableList<Int>>()
        var row = ArrayList<Int>()
        var w = 0f
        tokens.forEachIndexed { i, tok ->
            if (row.isNotEmpty() && w + tok.width > wrapW) {
                rows += row
                row = ArrayList()
                w = 0f
            }
            row += i
            w += tok.width + tok.gap
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    /**
     * 画一个块：以 (cx,cy) 为中心整体缩放；每字 = 升起渐显 × 深度明暗 × 块透明度，叠漂浮游走
     * 与随机微倾；[glowOn]（当前句）时按默认样式同款发光（定半径光晕、能量调透明度，画在
     * 填充之下）；[blurRadius]>0（远处句）时叠轻微模糊增强纵深。
     */
    private fun drawBlock(
        canvas: Canvas,
        b: Block,
        cx: Float,
        cy: Float,
        scale: Float,
        blockAlpha: Float,
        now: Long,
        effPos: Long,
        glowOn: Boolean,
        forceAppear: Boolean,
        blurRadius: Float,
    ) {
        if (blockAlpha <= 0.004f) return
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-b.width / 2f, -b.height / 2f)

        val tSec = now / 1000f
        val glowStrength = if (glowOn && glowEnabled) rhythmGlow() else 0f
        val glowFilter = if (glowStrength > 0.01f) blurFilterFor(b.base * ST_GLOW_EM) else null
        val blurFilter = if (blurRadius > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            blurFilterFor(blurRadius)
        } else {
            null
        }

        for (tok in b.drawOrder) {
            val t = when {
                forceAppear -> 1f
                b.timed -> ((effPos - tok.appearAt).toFloat() / ST_RISE_MS).coerceIn(0f, 1f)
                else -> ((now - b.bornAt - tok.appearDelay).toFloat() / ST_RISE_MS).coerceIn(0f, 1f)
            }
            if (t <= 0f) continue
            val e = easeOutCubic(t)
            val rise = (1f - e) * b.base * ST_RISE_DIST_EM
            val fx = sin(tSec * tok.floatW1 + tok.floatP1) * tok.ampXF * tok.size
            val fy = cos(tSec * tok.floatW2 + tok.floatP2) * tok.ampYF * tok.size
            val alpha = (255f * e * tok.depthAlpha * blockAlpha).roundToInt().coerceIn(0, 255)
            if (alpha == 0) continue
            paint.textSize = tok.size
            val x = tok.x + fx
            val y = tok.baseline + tok.dy + rise + fy
            // 随机左右微倾：绕字视觉中心旋转。
            canvas.save()
            canvas.rotate(tok.tiltDeg, x + tok.width / 2f, y - tok.size * 0.35f)
            if (glowFilter != null) {
                paint.maskFilter = glowFilter
                paint.alpha = (alpha * glowStrength).roundToInt().coerceIn(0, 255)
                canvas.drawText(tok.text, x, y, paint)
                paint.maskFilter = null
            }
            if (blurFilter != null) paint.maskFilter = blurFilter
            paint.alpha = alpha
            canvas.drawText(tok.text, x, y, paint)
            if (blurFilter != null) paint.maskFilter = null
            canvas.restore()
        }
        canvas.restore()
    }

    // ---- 右上角时钟 ----

    private var clockText = ""
    private var clockMinute = -1L
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun currentClockText(): String {
        val minute = System.currentTimeMillis() / 60000L
        if (minute != clockMinute) {
            clockMinute = minute
            clockText = clockFormat.format(Date())
        }
        return clockText
    }

    /** 位置对齐默认模式的停靠时钟：贴右上角 10dp、垂直中心=停靠封面中心高度。 */
    private fun drawClock(canvas: Canvas, alpha: Float) {
        val margin = dp(ST_CLOCK_MARGIN_DP)
        val centerY = margin + dp(ST_CLOCK_BOX_DP) / 2f
        clockPaint.textSize = dp(ST_CLOCK_TEXT_DP)
        clockPaint.alpha = (235f * alpha).roundToInt().coerceIn(0, 255)
        val fm = clockPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val text = currentClockText()
        val tw = clockPaint.measureText(text)
        canvas.drawText(text, width - margin - tw, baseline, clockPaint)
    }

    // ---- 工具 ----

    /** 词内拆分：汉字逐字（时间按 100px 测宽比例均分），其它语言保持整词。返回 (文本, 出字时刻)。 */
    private fun splitWord(w: LyricWord): List<Pair<String, Long>> {
        val text = w.text.orEmpty()
        if (text.isEmpty()) return emptyList()
        val parts = splitPlain(text)
        if (parts.size <= 1) return listOf(text to w.begin)
        paint.textSize = 100f
        val widths = parts.map { paint.measureText(it) }
        val total = widths.sum().coerceAtLeast(0.001f)
        val dur = (w.end - w.begin).coerceAtLeast(0L)
        var cum = 0f
        return parts.mapIndexed { i, part ->
            val b = w.begin + (dur * (cum / total)).toLong()
            cum += widths[i]
            part to b
        }
    }

    /** CJK 单字、其它按空格聚词（与 FullLyricView 同规则）。 */
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
        return out.filter { it.isNotBlank() }
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x2E80..0x9FFF) || (c in 0xAC00..0xD7AF) || (c in 0xF900..0xFAFF) || (c in 0xFF00..0xFFEF)
    }

    /** 半径量化到整数像素做缓存（BlurMaskFilter 构造不便宜；发光/远处模糊共用）。 */
    private fun blurFilterFor(radius: Float): BlurMaskFilter {
        val r = radius.roundToInt().coerceAtLeast(1)
        return blurCache.getOrPut(r) { BlurMaskFilter(r.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    private fun easeOutCubic(t: Float): Float {
        val c = 1f - t.coerceIn(0f, 1f)
        return 1f - c * c * c
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private val twoPi = (2.0 * Math.PI).toFloat()

    /** 漂浮角频率（rad/s）：周期 3.4~6.6s 随机。 */
    private fun periodSec(rng: Random): Float =
        (ST_FLOAT_PERIOD_MIN_MS + rng.nextFloat() * ST_FLOAT_PERIOD_VAR_MS) / 1000f
}
