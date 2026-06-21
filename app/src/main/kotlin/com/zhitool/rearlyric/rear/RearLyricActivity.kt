package com.zhitool.rearlyric.rear

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.zhitool.rearlyric.core.MediaControl
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.lyric.CoverPosition
import com.zhitool.rearlyric.lyric.CoverShape
import com.zhitool.rearlyric.lyric.LyricBus
import com.zhitool.rearlyric.lyric.LyricColors
import com.zhitool.rearlyric.lyric.LyricFrameRate
import com.zhitool.rearlyric.lyric.PackageStyleState
import com.zhitool.rearlyric.lyric.RearConfigState
import com.zhitool.rearlyric.lyric.TextColorMode
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class RearLyricActivity : ComponentActivity() {

    private var selfHealJob: Job? = null

    // 是否已在副屏：未到副屏前作透明、不可触的占位（不渲染内容、不吞正面触控），到副屏才显示。
    private val onRear = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        RearStage.enter(this)
        LyricBus.rearTaskId.value = taskId
        updateRearGate()
        setContent { if (onRear.value) RearLyricScreen() }

        lifecycleScope.launch {
            LyricBus.projected.collect { projected -> if (!projected) finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        // 锁屏解锁/息屏唤醒后 flags 可能被系统重置，参照 MRSS 在 onResume 重申。
        applyLockScreenFlags()
        LyricBus.rearTaskId.value = taskId
        updateRearGate()
        com.zhitool.rearlyric.tools.notify.NotifyBus.pillHostResumed(this)
        ensureOnRearDisplay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 任务在屏间移动会触发配置变化（已声明 configChanges 不重建），据此更新占位/显示。
        updateRearGate()
    }

    /** 根据当前所在 display 切换「占位(透明不可触)↔显示」。 */
    private fun updateRearGate() {
        val rear = currentDisplayId() == REAR_DISPLAY_ID
        onRear.value = rear
        val placeholder = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (rear) window.clearFlags(placeholder) else window.addFlags(placeholder)
    }

    override fun onPause() {
        super.onPause()
        com.zhitool.rearlyric.tools.notify.NotifyBus.pillHostPaused(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        RearStage.leave(this)
        com.zhitool.rearlyric.tools.notify.NotifyBus.pillHostPaused(this)
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

    // 全量模式：点封面放大成控制面板（收藏/切歌/暂停 + “词”返回，不自动返回）。
    var coverPanelExpanded by remember { mutableStateOf(false) }
    // 拖动调整进度：FullLyricView 上报「正中那句」的时间，在此全屏层把时间画到歌词左侧
    // （可溢出到左侧摄像头空隙——FullLyricView 自身只占右 2/3 画不到那里）。
    var scrubActive by remember { mutableStateOf(false) }
    var scrubTimeMs by remember { mutableStateOf(0L) }
    // 长按蓄力进度环 + 小锁(画在歌词左侧时间处)，参数由 FullLyricView 上报。
    var lockVisible by remember { mutableStateOf(false) }
    var lockRingProgress by remember { mutableFloatStateOf(0f) }
    var lockRingAlpha by remember { mutableFloatStateOf(0f) }
    var lockOpen by remember { mutableFloatStateOf(0f) }
    var lockAlpha by remember { mutableFloatStateOf(0f) }
    // 控制面板「音量」按钮：点击后进度条变成音量条；1.5s 未触控自动还原成进度条。
    var volumeMode by remember { mutableStateOf(false) }
    var volumeTick by remember { mutableStateOf(0) }
    LaunchedEffect(coverPanelExpanded) { if (!coverPanelExpanded) volumeMode = false }
    LaunchedEffect(volumeMode, volumeTick) { if (volumeMode) { delay(1500); volumeMode = false } }
    // 面板展开进度（0=收起，1=展开）：同时驱动 FullLyricView 的封面放大/歌词淡出、黑底淡入、按键升起。
    // 时长与 dock 放大动画接近，整体观感一致。
    val panelProgress by animateFloatAsState(
        targetValue = if (coverPanelExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
        label = "coverPanelProgress",
    )

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

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedCoverBackground(bgColors)

        // 控制面板暗底：铺满整屏（含左侧非安全区），压暗背景——歌词在 FullLyricView 内淡到背景亮度（不消失），
        // 此暗底在其下压暗整体，封面/进度条/按键叠其上；歌词作为暗背景隐约可见（最早方案）。
        if (panelProgress > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.66f * panelProgress))
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val showCover = coverBitmap != null && cfg.cover != CoverPosition.NONE
            // 左安全区：控制视图左边界（负=视图向左加宽、伸进左侧区，可越过 1/3；正=左缘右移、视图变窄）。
            // 视图右缘仍贴屏幕右边（CenterEnd），角落小封面贴真实右上角。右安全区仍走视图内右内边距。
            val viewLeftEdge = lyricLeftEdgeDp(maxWidth, cfg.safeAreaLeft)
            val contentWidth = maxWidth - viewLeftEdge
            // 内部左文字内边距固定（不随安全区变；安全区已由视图左缘体现）。
            val padLeftPx = with(density) { 18.dp.roundToPx() }
            val padTopPx = rearDisplayInfo.cutout.top + with(density) { 22.dp.roundToPx() }
            // 右安全区每步 1dp；默认 0=在原基准上再内缩 6dp（即旧版值 6 的位置）。
            val safeStepPx = with(density) { 1.dp.roundToPx() }
            val padRightPx = ((rearDisplayInfo.cutout.right / 2) + with(density) { (11 + 6).dp.roundToPx() } - (cfg.safeAreaRight + 6) * safeStepPx).coerceAtLeast(0)
            val padBottomPx = rearDisplayInfo.cutout.bottom + with(density) { 22.dp.roundToPx() }
            AndroidView(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(contentWidth)
                    .fillMaxHeight(),
                factory = { ctx ->
                    FullLyricView(ctx).apply {
                        onCoverTap = { coverPanelExpanded = !coverPanelExpanded }
                        onScrubChange = { active, t ->
                            scrubActive = active
                            scrubTimeMs = t
                        }
                        onLockVisual = { visible, ringP, ringA, open, alpha ->
                            lockVisible = visible
                            lockRingProgress = ringP
                            lockRingAlpha = ringA
                            lockOpen = open
                            lockAlpha = alpha
                        }
                        onSeek = { ms ->
                            val pkg = LyricBus.playerPackage.value
                            MediaControl.seekTo(ctx, pkg, ms)
                            // 跳转后开始播放（若当前暂停）。
                            if (!LyricBus.playing.value) MediaControl.playPause(ctx, pkg)
                        }
                    }
                },
                update = { view ->
                    view.setPadding(padLeftPx, padTopPx, padRightPx, padBottomPx)
                    // 面板展开进度：驱动封面回到「首句前大封面」、歌词淡到背景（复用 FullLyricView 现成排版/动画）。
                    view.setPanelProgress(panelProgress)
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
        }

        // 背屏通知覆盖层：歌词上方弹胶囊（图标+N条新通知），点击就地铺满成胶囊页，不跳转。
        com.zhitool.rearlyric.tools.notify.RearNotifyOverlay(modifier = Modifier.fillMaxSize())

        // 控制面板「进度条层」：大封面与按键之间，左当前时间 / 右总时长 / 可拖动 seek（取自 MediaSession）。
        CoverPanelProgress(
            progress = panelProgress,
            positionMs = currentPosition,
            playerPackage = playerPackage,
            volumeMode = volumeMode,
            onVolumeInteract = { volumeTick++ },
        )

        // 控制面板「按键层」：底部升起渐显的一行控制（收藏/上一首/暂停·播放/下一首 + 词）。
        // 封面与歌名/歌手由 FullLyricView 的「首句前大封面」直接呈现（黑底在其下），这里只叠按键。
        CoverPanelControls(
            expanded = coverPanelExpanded,
            progress = panelProgress,
            playing = playing,
            playerPackage = playerPackage,
            onVolume = { volumeMode = true; volumeTick++ },
        )

        // 拖动调整进度时，正中那句歌词左侧显示「时间 + 细横线」单行。细横线左端固定（=原 11dp 线的左端，
        // 在歌词区左缘内侧），向右延长到 1.8 倍（多出部分往右、溢入歌词侧）；时间右对齐结束于线左端前。
        if (scrubActive) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // 跟随歌词视图左缘（含左安全区微调），否则视图左移后时间会压到歌词上。
                val lyricEdge = lyricLeftEdgeDp(maxWidth, cfg.safeAreaLeft)
                // 时间+细线左右微调（每步约 2dp，同步移动时间与线左端）；细线长度=cfg.timeLineLength dp（绝对，左端固定向右伸缩）。
                val lineLeft = lyricEdge - 19.dp + (cfg.timeOffset * 2).dp
                val lineW = cfg.timeLineLength.dp.coerceAtLeast(2.dp)
                // 时间：右对齐结束于线左端前 4dp（可溢出到左 1/3 摄像头上下排版的中间空隙）。
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width((lineLeft - 4.dp).coerceAtLeast(0.dp)),
                ) {
                    Text(
                        text = formatTime(scrubTimeMs),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.End,
                        style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 10f)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = lineLeft)
                        .width(lineW)
                        .height(1.dp)
                        .background(Color.White),
                )
            }
        }

        // 长按蓄力进度环 + 小锁：画在歌词左侧时间位置(竖直居中=摄像头中间空隙)。
        if (lockVisible) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // 跟随歌词视图左缘（含左安全区微调）。
                val viewLeft = lyricLeftEdgeDp(maxWidth, cfg.safeAreaLeft)
                // 小锁外圈半径=cfg.lockSize dp（锁与圈同步，box=半径*2，默认 17→34dp）；缩放时圈心不动。
                // 左右微调：每步约 2dp，+右 -左。
                val lockBox = (cfg.lockSize * 2).dp.coerceAtLeast(12.dp)
                // 默认 0=在原基准上再左移 54dp（即旧版值 -27 的位置）。
                val lockCenterX = viewLeft - 77.dp + (cfg.lockOffset * 2).dp
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = lockCenterX - lockBox / 2)
                        .size(lockBox),
                ) {
                    LockBadge(
                        ringProgress = lockRingProgress,
                        ringAlpha = lockRingAlpha,
                        lockOpen = lockOpen,
                        lockAlpha = lockAlpha,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 紧急关闭按钮：左侧摄像头空白区"上二分之一"的正中。正常投在背屏时被
        // 摄像头模组物理遮挡；一旦误投到正面屏幕，点它即可关闭歌词页。控制面板之上仍可点。
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

/**
 * 控制面板进度条层：大封面与按键之间，左=当前时间 / 中=可拖动进度条 / 右=歌曲总时长。
 * 进度与时长取自系统媒体会话（[MediaControl]，即通知上的进度），拖动/点击=seek。随面板进度淡入淡出。
 */
@Composable
private fun CoverPanelProgress(
    progress: Float,
    positionMs: Long,
    playerPackage: String?,
    volumeMode: Boolean,
    onVolumeInteract: () -> Unit,
) {
    if (progress <= 0.001f) return
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var duration by remember { mutableStateOf(0L) }
    LaunchedEffect(playerPackage) {
        while (true) {
            duration = withContext(Dispatchers.Default) { MediaControl.readDuration(context, playerPackage) }
            delay(1000)
        }
    }
    // 进入音量模式时读取系统当前音量。
    var volFrac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(volumeMode) {
        if (volumeMode) volFrac = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
    }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val dur = duration.coerceAtLeast(1L)
    val frac = when {
        dragging -> dragFraction
        volumeMode -> volFrac
        else -> (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    }
    val leftLabel = if (volumeMode) "音量" else formatTime(if (dragging) (dragFraction * dur).toLong() else positionMs)
    val rightLabel = if (volumeMode) "${(frac * 100f).roundToInt()}%" else formatTime(duration)

    fun setVolume(f: Float) {
        val cf = f.coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (cf * maxVol).roundToInt().coerceIn(0, maxVol), 0)
        volFrac = cf
        onVolumeInteract()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val regionCenterX = maxWidth * (2f / 3f)
        val rowW = maxWidth * (2f / 3f) - 24.dp
        Row(
            modifier = Modifier
                .offset(x = regionCenterX - rowW / 2f, y = maxHeight * 0.58f)
                .width(rowW)
                .graphicsLayer { alpha = progress },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(leftLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
                    .pointerInput(dur, volumeMode) {
                        detectTapGestures { offset ->
                            val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            if (volumeMode) setVolume(f)
                            else MediaControl.seekTo(context, playerPackage, (f * dur).toLong())
                        }
                    }
                    .pointerInput(dur, volumeMode) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                if (volumeMode) setVolume(dragFraction)
                            },
                            onHorizontalDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                if (volumeMode) setVolume(dragFraction)
                            },
                            onDragEnd = {
                                if (!volumeMode) MediaControl.seekTo(context, playerPackage, (dragFraction * dur).toLong())
                                dragging = false
                            },
                            onDragCancel = { dragging = false },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                    val cy = size.height / 2f
                    val track = 3.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.3f),
                        start = Offset(0f, cy),
                        end = Offset(size.width, cy),
                        strokeWidth = track,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(0f, cy),
                        end = Offset(size.width * frac, cy),
                        strokeWidth = track,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * frac, cy))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(rightLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 长按蓄力进度环 + 小锁徽标（画在歌词左侧时间处）。
 * [ringProgress] 0..1 顺时针填充弧；[lockOpen] 0=合锁/1=开锁(锁梁上移右倾)；alpha 控制淡入淡出。
 */
@Composable
private fun LockBadge(
    ringProgress: Float,
    ringAlpha: Float,
    lockOpen: Float,
    lockAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = 3.dp.toPx()
        val ringR = size.minDimension / 2f - stroke
        // 深色底圆：浅色歌词上也能看清。
        val scrimA = 0.42f * max(ringAlpha, lockAlpha).coerceIn(0f, 1f)
        if (scrimA > 0.01f) {
            drawCircle(Color.Black.copy(alpha = scrimA), radius = ringR + stroke, center = Offset(cx, cy))
        }
        if (ringAlpha > 0.01f) {
            drawCircle(
                color = Color.White.copy(alpha = 0.22f * ringAlpha),
                radius = ringR,
                center = Offset(cx, cy),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color.White.copy(alpha = ringAlpha),
                startAngle = -90f,
                sweepAngle = 360f * ringProgress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(cx - ringR, cy - ringR),
                size = Size(ringR * 2f, ringR * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        val la = lockAlpha.coerceIn(0f, 1f)
        if (la > 0.01f) {
            val lockSize = ringR
            val bodyW = lockSize * 0.62f
            val bodyH = lockSize * 0.48f
            // 锁体顶部稍微上移，让整把小锁在圆圈内偏上一点。
            val bodyTop = cy - lockSize * 0.1f
            drawRoundRect(
                color = Color.White.copy(alpha = la),
                topLeft = Offset(cx - bodyW / 2f, bodyTop),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(lockSize * 0.1f, lockSize * 0.1f),
            )
            val shackleR = bodyW * 0.34f
            val shackleCy = bodyTop - lockOpen.coerceIn(0f, 1f) * lockSize * 0.18f
            drawArc(
                color = Color.White.copy(alpha = la),
                startAngle = 180f + lockOpen.coerceIn(0f, 1f) * 25f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - shackleR, shackleCy - shackleR),
                size = Size(shackleR * 2f, shackleR * 2f),
                style = Stroke(width = lockSize * 0.12f, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 歌词视图左边界（= 内容区左缘，也是左侧小锁/时间的锚点）。
 * 基准在屏幕 1/3 处；左安全区微调每步 1dp：负值让视图向左加宽、伸进左侧摄像头区（不再卡在 1/3 处），
 * 正值把左缘右移、视图变窄。夹在 [0, maxWidth-100dp] 之间。
 */
private fun lyricLeftEdgeDp(maxWidth: Dp, safeAreaLeft: Int): Dp =
    (maxWidth / 3f - 23.dp + safeAreaLeft.dp).coerceIn(0.dp, (maxWidth - 100.dp).coerceAtLeast(0.dp))

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    return "%02d:%02d".format(totalSec / 60L, totalSec % 60L)
}

/**
 * 控制面板按键层：全量模式点封面后，封面与歌名/歌手由 [FullLyricView] 的「首句前大封面」直接呈现
 * （复用其放大/旋转/排版，黑底由调用方铺在其下），这里只在底部叠一行控制——从底部升起渐显（非线性）：
 * 收藏 / 上一首 / 暂停·播放 / 下一首 / 词。点「词」或点放大后的封面（FullLyricView 的封面命中区）返回歌词。
 * 媒体控制走 [MediaControl]；**没有收藏键的播放器（适配不全）直接不显示收藏键，按 4 键排版**。
 *
 * @param progress 0=收起，1=展开（调用方驱动，与封面放大/黑底淡入同源）。
 */
@Composable
private fun CoverPanelControls(
    expanded: Boolean,
    progress: Float,
    playing: Boolean,
    playerPackage: String?,
    onVolume: () -> Unit,
) {
    // 完全收起后不占位、不拦截触控（退出动画跑完才移除）。
    if (!expanded && progress <= 0.001f) return

    val context = LocalContext.current
    var media by remember { mutableStateOf(MediaControl.State()) }
    // 收藏点击后用它触发一次延时确认重读（等播放器更新会话状态）。
    var favTick by remember { mutableStateOf(0) }

    LaunchedEffect(expanded, playerPackage) {
        if (expanded) media = withContext(Dispatchers.Default) { MediaControl.readState(context, playerPackage) }
    }
    LaunchedEffect(favTick) {
        if (favTick > 0 && expanded) {
            delay(450)
            media = withContext(Dispatchers.Default) { MediaControl.readState(context, playerPackage) }
        }
    }

    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 居中于右 2/3（避开左 1/3 摄像头），位于下半部；没有收藏键则 4 键。
        val regionCenterX = maxWidth * (2f / 3f)
        val buttonCount = if (media.canFavorite) 5 else 4
        val rowW = (buttonCount * 44).dp
        val riseY = maxHeight * 0.72f
        val risePx = with(density) { 36.dp.toPx() }

        Row(
            modifier = Modifier
                .offset(x = regionCenterX - rowW / 2f, y = riseY)
                .width(rowW)
                .graphicsLayer {
                    // 从底部升起 + 渐显（progress 已非线性 ease）。
                    alpha = progress
                    translationY = (1f - progress) * risePx
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (media.canFavorite) {
                PanelButton(
                    onClick = {
                        val ok = MediaControl.toggleFavorite(context, playerPackage)
                        if (ok) {
                            media = media.copy(isFavorited = !media.isFavorited) // 乐观更新
                            favTick++
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (media.isFavorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (media.isFavorited) Color(0xFFFF4D6A) else Color.White,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
            PanelButton(onClick = { MediaControl.previous(context, playerPackage) }) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            PanelButton(onClick = { MediaControl.playPause(context, playerPackage) }) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            PanelButton(onClick = { MediaControl.next(context, playerPackage) }) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            // 音量：点击后下方进度条变为音量调整条（1.5s 未触控自动还原成进度条）。返回歌词改为点封面。
            PanelButton(onClick = { onVolume() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "音量",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** 控制面板上统一尺寸的圆形按钮（内容可为图标或“词”文字）。 */
@Composable
private fun PanelButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        content()
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

