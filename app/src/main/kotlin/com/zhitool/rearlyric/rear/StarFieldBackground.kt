package com.zhitool.rearlyric.rear

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 星星数量恒定（消一颗即补一颗，进出数量平稳）。 */
private const val STAR_COUNT = 130
/**
 * 后退脉冲：与上一句歌词同拍同缓动——收敛方向=歌词退去的消失点，近星（depth 大）每脉冲
 * 缩小约一半（与歌词 1→0.4x 的缩幅一致）、收敛过半，远星几乎不动（视差=3D 感）。
 */
private const val PULSE_CONVERGE = 0.5f
private const val PULSE_SHRINK = 0.55f
/** 前进脉冲（关闭封面页"变近"）：星星远离消失点、变大变亮。 */
private const val PULSE_GROW = 0.8f
/** 缩小到该"体积"（深度）即淡出消失；长大超过上限或被推出屏幕外也消亡（前进时）。 */
private const val DEATH_DEPTH = 0.16f
private const val MAX_DEPTH = 1.25f
private const val OFFSCREEN_KILL = 0.10f
/** 新星从屏幕边缘外侧这么远处进入（归一坐标），等脉冲把它拉进画面。 */
private const val SPAWN_EDGE_MIN = 0.02f
private const val SPAWN_EDGE_VAR = 0.06f
/** 入场淡入 / 消亡淡出时长。 */
private const val FADE_MS = 260f
/** 静止期漂浮：位置正弦游走振幅（dp，近星摆动更大）与角频率范围（rad/s）。 */
private const val WANDER_AMP_DP = 4.2f
private const val WANDER_W_MIN = 0.18f
private const val WANDER_W_VAR = 0.5f

/** 星星色板：以白为主，少量冷蓝与暖黄点缀。 */
private val STAR_TINTS = listOf(
    Color.White, Color.White, Color.White, Color.White,
    Color(0xFFBFD4FF), Color(0xFFA8C4FF),
    Color(0xFFFFE7C4),
)

/**
 * 星空与歌词的运动联动：切句 / 开合封面页时由歌词侧发脉冲（带消失点，全屏归一坐标）。
 * [forward]=false 是后退（切句/开封面页：星星向消失点收敛缩小，与上一句歌词的退去方向、
 * 缩幅一致）；true 是前进（关闭封面页"变近"：星星远离消失点、变大）。平时星星只有
 * 上下左右的轻微漂浮与闪烁——歌词动星星才动。
 */
internal class StarMotion {
    var seq = 0
        private set
    var atMs = 0L
        private set
    var durMs = 780L
        private set
    var vpX = 0.5f
        private set
    var vpY = 0.5f
        private set
    var forward = false
        private set

    fun pulse(vpX: Float, vpY: Float, durMs: Long, forward: Boolean = false) {
        this.vpX = vpX.coerceIn(0f, 1f)
        this.vpY = vpY.coerceIn(0f, 1f)
        this.durMs = durMs.coerceAtLeast(1L)
        this.forward = forward
        atMs = SystemClock.elapsedRealtime()
        seq++
    }
}

private class Star {
    /** 基准位置（归一坐标，可在屏幕外；漂浮偏移不改它，脉冲才推它）。 */
    var x = 0f
    var y = 0f
    /** 深度 0（远，小而暗）..1+（近，大而亮）：决定视差幅度、尺寸、亮度。 */
    var depth = 0f
    var sizeMult = 1f
    var twinkleFreq = 0f
    var twinklePhase = 0f
    var wanderW1 = 0f
    var wanderW2 = 0f
    var wanderP1 = 0f
    var wanderP2 = 0f
    var tint = Color.White
    /** 入场淡入起点（墙钟）与消亡淡出起点（0=存活）。 */
    var bornAt = 0L
    var dyingAt = 0L

    private fun rollCommon(rng: Random, now: Long) {
        sizeMult = 0.5f + rng.nextFloat() * 1.3f
        twinkleFreq = 0.6f + rng.nextFloat() * 1.8f
        twinklePhase = rng.nextFloat() * TWO_PI
        wanderW1 = WANDER_W_MIN + rng.nextFloat() * WANDER_W_VAR
        wanderW2 = WANDER_W_MIN + rng.nextFloat() * WANDER_W_VAR
        wanderP1 = rng.nextFloat() * TWO_PI
        wanderP2 = rng.nextFloat() * TWO_PI
        tint = STAR_TINTS[rng.nextInt(STAR_TINTS.size)]
        bornAt = now
        dyingAt = 0L
    }

    /** 初始铺场：屏幕内均匀分布、立即可见（只在页面刚打开时用一次）。 */
    fun spawnInside(rng: Random, now: Long) {
        rollCommon(rng, now)
        x = rng.nextFloat()
        y = rng.nextFloat()
        depth = 0.2f + rng.nextFloat() * 0.8f
        bornAt = now - FADE_MS.toLong()
    }

    /** 常规重生：屏幕外某条边缘外侧、近景深度，淡入等待脉冲把它拉进画面。 */
    fun spawnFromEdge(rng: Random, now: Long) {
        rollCommon(rng, now)
        val off = SPAWN_EDGE_MIN + rng.nextFloat() * SPAWN_EDGE_VAR
        when (rng.nextInt(4)) {
            0 -> { x = rng.nextFloat(); y = -off }
            1 -> { x = rng.nextFloat(); y = 1f + off }
            2 -> { x = -off; y = rng.nextFloat() }
            else -> { x = 1f + off; y = rng.nextFloat() }
        }
        depth = 0.7f + rng.nextFloat() * 0.3f
    }

    /** 前进脉冲后的重生：从消失点附近以远景深度出现（随后被继续推近推大）。 */
    fun spawnNearVp(rng: Random, now: Long, vpX: Float, vpY: Float) {
        rollCommon(rng, now)
        val ang = rng.nextFloat() * TWO_PI
        val r = 0.03f + rng.nextFloat() * 0.14f
        x = (vpX + cos(ang) * r).coerceIn(0f, 1f)
        y = (vpY + sin(ang) * r).coerceIn(0f, 1f)
        depth = 0.18f + rng.nextFloat() * 0.18f
    }
}

private const val TWO_PI = (Math.PI * 2).toFloat()

/**
 * 错位交替样式的背景：流动的星空。星星平时只轻微漂浮闪烁；[motion] 发脉冲时按深度视差
 * 与歌词同向运动——后退：向消失点收敛缩小，缩到 [DEATH_DEPTH] 淡出消失，同时从屏幕外
 * 补进新星（总数恒定，进出平稳）；前进（关封面页）：远离消失点变大变近，推出屏幕即换新。
 * 星点用光晕+核心双层画出柔和边缘，2D 画出 3D。
 */
@Composable
internal fun StarFieldBackground(motion: StarMotion, modifier: Modifier = Modifier) {
    val rng = remember { Random(System.nanoTime()) }
    val stars = remember {
        val now = SystemClock.elapsedRealtime()
        List(STAR_COUNT) { Star().apply { spawnInside(rng, now) } }
    }
    var frameNanos by remember { mutableLongStateOf(0L) }
    // 脉冲进度跟踪：每帧只应用增量收敛（seq 变了从 0 重新积）。
    val pulseTrack = remember { floatArrayOf(0f, -1f) } // [lastEased, lastSeq]

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(modifier = modifier) {
        val t = frameNanos / 1e9f
        val now = SystemClock.elapsedRealtime()

        drawDeepSpace()

        // 脉冲增量：本帧相对上帧新增的收敛比例（easeOutCubic，与歌词后缩同缓动）。
        var dc = 0f
        if (motion.seq > 0) {
            if (pulseTrack[1] != motion.seq.toFloat()) {
                pulseTrack[1] = motion.seq.toFloat()
                pulseTrack[0] = 0f
            }
            val raw = ((now - motion.atMs).toFloat() / motion.durMs).coerceIn(0f, 1f)
            val inv = 1f - raw
            val eased = 1f - inv * inv * inv
            dc = (eased - pulseTrack[0]).coerceAtLeast(0f)
            pulseTrack[0] = eased
        }

        val wanderAmp = WANDER_AMP_DP * density
        for (s in stars) {
            if (dc > 0f && s.dyingAt == 0L) {
                // 视差系数：近星动得多、缩/涨得快，远星几乎不动。
                val para = 0.3f + 0.9f * s.depth.coerceIn(0f, 1f)
                if (!motion.forward) {
                    // 后退：与上一句歌词同向——向消失点收敛 + 缩小。
                    val k = dc * PULSE_CONVERGE * para
                    s.x += (motion.vpX - s.x) * k
                    s.y += (motion.vpY - s.y) * k
                    s.depth *= 1f - dc * PULSE_SHRINK * para
                    if (s.depth < DEATH_DEPTH) s.dyingAt = now
                } else {
                    // 前进（关封面页"变近"）：远离消失点 + 变大。
                    val k = dc * PULSE_CONVERGE * para
                    s.x += (s.x - motion.vpX) * k
                    s.y += (s.y - motion.vpY) * k
                    s.depth *= 1f + dc * PULSE_GROW * para
                    val outside = s.x < -OFFSCREEN_KILL || s.x > 1f + OFFSCREEN_KILL ||
                        s.y < -OFFSCREEN_KILL || s.y > 1f + OFFSCREEN_KILL
                    if (s.depth > MAX_DEPTH || outside) s.dyingAt = now
                }
            }

            // 消亡淡出 → 重生：后退消亡从屏幕外补入；前进消亡从消失点附近补入。
            var deathFade = 1f
            if (s.dyingAt != 0L) {
                val ft = (now - s.dyingAt) / FADE_MS
                if (ft >= 1f) {
                    if (motion.forward) s.spawnNearVp(rng, now, motion.vpX, motion.vpY)
                    else s.spawnFromEdge(rng, now)
                } else {
                    deathFade = 1f - ft
                }
            }
            val bornFade = ((now - s.bornAt) / FADE_MS).coerceIn(0f, 1f)

            // 静止期漂浮：只有上下左右的正弦游走（不累积、无径向运动），近星摆动更大。
            val d = s.depth.coerceIn(0f, 1f)
            val amp = wanderAmp * (0.35f + 0.85f * d)
            val fx = sin(t * s.wanderW1 + s.wanderP1) * amp
            val fy = cos(t * s.wanderW2 + s.wanderP2) * amp
            val twinkle = 0.72f + 0.28f * sin(t * s.twinkleFreq + s.twinklePhase)
            val alpha = ((0.12f + 0.88f * d) * twinkle * deathFade * bornFade).coerceIn(0f, 1f)
            if (alpha <= 0.004f) continue
            val radius = (0.6f + 2.1f * d) * s.sizeMult * density
            val center = Offset(s.x * size.width + fx, s.y * size.height + fy)
            // 光晕 + 核心双层：柔和边缘，与歌词远去的模糊观感一致。
            drawCircle(color = s.tint, radius = radius * 2.1f, center = center, alpha = alpha * 0.20f)
            drawCircle(color = s.tint, radius = radius, center = center, alpha = alpha)
        }
    }
}

/** 深空底色：近黑的竖向渐变 + 极淡的偏蓝星云高光（中心略上），避免死黑一片。 */
private fun DrawScope.drawDeepSpace() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF030510), Color(0xFF080D20), Color(0xFF04060F)),
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x2E2B4A86), Color(0x00000000)),
            center = Offset(size.width * 0.62f, size.height * 0.38f),
            radius = size.maxDimension * 0.75f,
        ),
    )
}
