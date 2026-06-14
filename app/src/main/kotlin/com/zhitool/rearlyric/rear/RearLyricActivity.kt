package com.zhitool.rearlyric.rear

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.lyric.CoverPosition
import com.zhitool.rearlyric.lyric.CoverShape
import com.zhitool.rearlyric.lyric.LyricBus
import com.zhitool.rearlyric.lyric.LyricColors
import com.zhitool.rearlyric.lyric.LyricDisplayMode
import com.zhitool.rearlyric.lyric.LyricFrameRate
import com.zhitool.rearlyric.lyric.PackageStyleState
import com.zhitool.rearlyric.lyric.RearConfigState
import com.zhitool.rearlyric.lyric.TextColorMode
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class RearLyricActivity : ComponentActivity() {

    private var selfHealJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        LyricBus.rearTaskId.value = taskId
        setContent { RearLyricScreen() }

        lifecycleScope.launch {
            LyricBus.projected.collect { projected -> if (!projected) finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        // 锁屏解锁/息屏唤醒后 flags 可能被系统重置，参照 MRSS 在 onResume 重申。
        applyLockScreenFlags()
        LyricBus.rearTaskId.value = taskId
        ensureOnRearDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (LyricBus.rearTaskId.value == taskId) LyricBus.rearTaskId.value = -1
    }

    /**
     * 自愈：投影器移屏失败（如 `am stack list` 解析超时）会把歌词页留在主屏，
     * 正面屏幕被盖住点不动。发现自己不在副屏时，用自己真实的 taskId 把任务移过去。
     * 延迟检查是给正常投屏流程（先拉起再移屏）让路，避免多余的 su 调用。
     */
    private fun ensureOnRearDisplay() {
        if (currentDisplayId() == REAR_DISPLAY_ID) return
        if (selfHealJob?.isActive == true) return
        selfHealJob = lifecycleScope.launch {
            repeat(MAX_SELF_HEAL_ATTEMPTS) {
                delay(SELF_HEAL_DELAY_MS)
                if (currentDisplayId() == REAR_DISPLAY_ID || !LyricBus.projected.value) return@launch
                if (RootShell.available) {
                    withContext(Dispatchers.IO) {
                        RootShell.run("service call activity_task 50 i32 $taskId i32 $REAR_DISPLAY_ID")
                    }
                }
            }
        }
    }

    private fun currentDisplayId(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { display?.displayId }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId
        }

    private fun applyLockScreenFlags() {
        // 新 API：锁屏时允许显示并点亮（旧 window flags 在新系统上可能被忽略）。
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    companion object {
        private const val REAR_DISPLAY_ID = 1
        private const val MAX_SELF_HEAL_ATTEMPTS = 3
        private const val SELF_HEAL_DELAY_MS = 1500L
    }
}

@Composable
private fun RearLyricScreen() {
    val baseCfg by RearConfigState.flow.collectAsState()
    val packageStyles by PackageStyleState.flow.collectAsState()
    val song by LyricBus.songFlow.collectAsState()
    val cover by LyricBus.cover.collectAsState()
    val playing by LyricBus.playingFlow.collectAsState()
    val playerPackage by LyricBus.playerPackage.collectAsState()
    val rearDisplayInfo by produceState(RearDisplayHelper.getRearDisplayInfo(), Unit) {
        value = withContext(Dispatchers.Default) { RearDisplayHelper.getRearDisplayInfo(forceRefresh = true) }
    }
    val cfg = packageStyles[playerPackage]?.config ?: baseCfg

    // 文字取色与背景取色解耦：任一需要封面色就提取；背景取色关闭时背景用默认深色，文字仍可用封面色。
    val needCover = cfg.dynamicBackground || cfg.textColorMode != TextColorMode.DEFAULT
    val coverColors by produceState(LyricColors.Default, cover, needCover) {
        value = if (needCover && cover != null) {
            withContext(Dispatchers.Default) { LyricColors.fromCover(cover) }
        } else {
            LyricColors.Default
        }
    }
    val bgColors = if (cfg.dynamicBackground) coverColors else LyricColors.Default
    val coverBitmap by produceState<ImageBitmap?>(null, cover) {
        value = cover?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val currentPosition by produceState(0L, cfg.frameRate, playing) {
        val frameIntervalNanos = when (cfg.frameRate) {
            LyricFrameRate.FPS_120 -> 1_000_000_000L / 120L
            LyricFrameRate.FPS_60 -> 1_000_000_000L / 60L
        }
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { frameTimeNanos ->
                // 心跳：息屏把 Activity stop 后帧时钟暂停、心跳停更，LyricService 据此自愈重投。
                LyricBus.markFrame()
                if (lastFrameNanos == 0L || frameTimeNanos - lastFrameNanos >= frameIntervalNanos) {
                    value = LyricBus.currentPosition()
                    lastFrameNanos = frameTimeNanos
                }
            }
        }
    }

    val density = LocalDensity.current
    val safePadding = with(density) {
        PaddingValues(
            top = rearDisplayInfo.cutout.top.toDp() + 22.dp,
            end = (rearDisplayInfo.cutout.right.toDp() / 2f) + 11.dp,
            bottom = rearDisplayInfo.cutout.bottom.toDp() + 22.dp,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedCoverBackground(bgColors)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val contentWidth = maxWidth * (2f / 3f)
            val showCover = coverBitmap != null && cfg.cover != CoverPosition.NONE
            val coverSize = (contentWidth * 0.24f).coerceIn(74.dp, 126.dp)
            // 歌名字号上限参考宽度：始终按"有封面"预留封面+间距（Column 内边距 18+6，封面与文字间距 16），
            // 不管实际是否显示封面，避免短歌名在无封面全宽时被放得过大。
            val titleSizingWidthPx = with(density) {
                (contentWidth - 24.dp - coverSize - 16.dp).coerceAtLeast(1.dp).toPx()
            }.toInt()
            val shouldShowLyricLayout = shouldShowLyricLayout(song, currentPosition, 300L)
            val entryProgress by animateFloatAsState(
                targetValue = if (shouldShowLyricLayout) 1f else 0f,
                animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                label = "firstLyricEntry",
            )
            val headerShiftPx = with(density) { (maxHeight * 0.205f).toPx() }
            val lyricLiftPx = with(density) { 38.dp.toPx() }

            if (cfg.displayMode == LyricDisplayMode.FULL_LYRIC) {
                // 全量歌词：视图铺到屏幕右 2/3 边缘（角落封面贴角、无视安全区），
                // 安全区作为视图内边距只约束歌词/引导卡；角落小封面用视图原始边界贴真实角。
                val padLeftPx = with(density) { 18.dp.roundToPx() }
                val padTopPx = rearDisplayInfo.cutout.top + with(density) { 22.dp.roundToPx() }
                val padRightPx = (rearDisplayInfo.cutout.right / 2) + with(density) { (11 + 6).dp.roundToPx() }
                val padBottomPx = rearDisplayInfo.cutout.bottom + with(density) { 22.dp.roundToPx() }
                AndroidView(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.CenterEnd)
                        .width(contentWidth)
                        .fillMaxHeight(),
                    factory = { ctx -> FullLyricView(ctx) },
                    update = { view ->
                        view.setPadding(padLeftPx, padTopPx, padRightPx, padBottomPx)
                        view.bind(
                            song = song,
                            positionMs = currentPosition,
                            config = cfg,
                            colors = coverColors,
                            playerPackage = playerPackage,
                            coverBitmap = if (showCover) coverBitmap?.asAndroidBitmap() else null,
                            playing = playing,
                        )
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(safePadding)
                ) {
                    // 换歌：整块信息（封面/歌名/歌词）从下方上滑顶上来。
                    val songSlidePx = with(density) { 64.dp.toPx() }
                    val songSlide = remember { Animatable(0f) }
                    LaunchedEffect(song) {
                        songSlide.snapTo(songSlidePx)
                        songSlide.animateTo(0f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
                    }
                    Column(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.CenterEnd)
                            .width(contentWidth)
                            .fillMaxHeight()
                            .padding(start = 18.dp, end = 6.dp)
                            .graphicsLayer { translationY = songSlide.value },
                    ) {
                        HeaderRow(
                            song = song,
                            showCover = showCover,
                            coverBitmap = coverBitmap,
                            coverSize = coverSize,
                            coverPosition = cfg.cover,
                            coverShape = cfg.coverShape,
                            playing = playing,
                            titleSizingWidthPx = titleSizingWidthPx,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.55f),
                            transitionProgress = entryProgress,
                            translationY = (1f - entryProgress) * headerShiftPx,
                        )
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.45f)
                                .graphicsLayer {
                                    alpha = entryProgress
                                    translationY = (1f - entryProgress) * lyricLiftPx
                                },
                            factory = { ctx ->
                                RearLyricRenderView(ctx).apply {
                                    setPadding(0, 0, 0, 0)
                                }
                            },
                            update = { view ->
                                view.bind(
                                    song = song,
                                    positionMs = currentPosition,
                                    config = cfg,
                                    colors = coverColors,
                                    previewLeadMs = 300L,
                                )
                            },
                        )
                    }
                }
            }
        }

        // 紧急关闭按钮：左侧摄像头空白区"上二分之一"的正中。正常投在背屏时被
        // 摄像头模组物理遮挡；一旦误投到正面屏幕，点它即可关闭歌词页。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(1f / 3f)
                .fillMaxHeight(0.5f),
            contentAlignment = Alignment.Center,
        ) {
            EmergencyCloseButton()
        }
    }
}

@Composable
private fun EmergencyCloseButton() {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { RearProjector.hide() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 2.5.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun HeaderRow(
    song: Song?,
    showCover: Boolean,
    coverBitmap: ImageBitmap?,
    coverSize: Dp,
    coverPosition: CoverPosition,
    coverShape: CoverShape,
    playing: Boolean,
    titleSizingWidthPx: Int,
    modifier: Modifier = Modifier,
    transitionProgress: Float = 1f,
    translationY: Float = 0f,
) {
    Row(
        modifier = modifier.graphicsLayer { this.translationY = translationY },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (showCover && coverBitmap != null && coverPosition == CoverPosition.LEFT) {
            CoverArt(bitmap = coverBitmap, coverSize = coverSize, coverShape = coverShape, playing = playing)
            Spacer(Modifier.width(16.dp))
        }
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .graphicsLayer { alpha = max(0.78f, transitionProgress) },
            factory = { ctx -> SongInfoBlockView(ctx) },
            update = { view -> view.bind(song, titleSizingWidthPx) },
        )
        if (showCover && coverBitmap != null && coverPosition == CoverPosition.RIGHT) {
            Spacer(Modifier.width(16.dp))
            CoverArt(bitmap = coverBitmap, coverSize = coverSize, coverShape = coverShape, playing = playing)
        }
    }
}

@Composable
private fun AnimatedCoverBackground(colors: LyricColors) {
    val transition = rememberInfiniteTransition(label = "bg")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse),
        label = "drift",
    )
    // 换歌时封面取色变化做渐变过渡，避免背景颜色硬切。
    val background by animateColorAsState(colors.background, tween(900, easing = FastOutSlowInEasing), label = "bg0")
    val backgroundEnd by animateColorAsState(colors.backgroundEnd, tween(900, easing = FastOutSlowInEasing), label = "bg1")
    val highlight by animateColorAsState(colors.highlight, tween(900, easing = FastOutSlowInEasing), label = "hi")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(background, backgroundEnd)))
        drawRect(
            Brush.radialGradient(
                colors = listOf(highlight.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width * (0.2f + 0.6f * t), size.height * (0.8f - 0.6f * t)),
                radius = size.maxDimension * 0.7f,
            )
        )
    }
}

@Composable
private fun CoverArt(
    bitmap: ImageBitmap,
    coverSize: Dp,
    coverShape: CoverShape,
    playing: Boolean,
) {
    val rotationSpeed = remember(bitmap, coverShape) { Animatable(0f) }
    var rotationAngle by remember(bitmap, coverShape) { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(
        targetValue = if (playing) 1.1f else 1f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "coverScale",
    )
    LaunchedEffect(bitmap, coverShape) {
        rotationSpeed.stop()
        rotationSpeed.snapTo(0f)
        rotationAngle = 0f
    }
    LaunchedEffect(bitmap, coverShape, playing) {
        if (coverShape != CoverShape.CIRCLE_ROTATE) {
            rotationSpeed.stop()
            rotationSpeed.snapTo(0f)
            rotationAngle = 0f
            return@LaunchedEffect
        }
        rotationSpeed.animateTo(
            targetValue = if (playing) 1f else 0f,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        )
    }
    LaunchedEffect(bitmap, coverShape) {
        if (coverShape != CoverShape.CIRCLE_ROTATE) return@LaunchedEffect
        var lastFrameNanos = 0L
        val degreesPerSecondAtFullSpeed = 360f / 16f
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                    rotationAngle = (rotationAngle + (degreesPerSecondAtFullSpeed * rotationSpeed.value * deltaSeconds)) % 360f
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }
    val clipShape = if (coverShape == CoverShape.SQUARE) RoundedCornerShape(4.dp) else CircleShape

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(coverSize)
            .aspectRatio(1f)
            .sizeIn(maxWidth = coverSize, maxHeight = coverSize)
            .graphicsLayer {
                rotationZ = if (coverShape == CoverShape.CIRCLE_ROTATE) rotationAngle else 0f
                scaleX = scale
                scaleY = scale
            }
            .clip(clipShape),
    )
}

private fun shouldShowLyricLayout(song: Song?, positionMs: Long, previewLeadMs: Long): Boolean {
    val firstLine = song?.lyrics
        ?.asSequence()
        ?.filter { !it.text.isNullOrBlank() }
        ?.minByOrNull { it.begin }
        ?: return false
    return positionMs >= firstLine.begin - previewLeadMs
}
