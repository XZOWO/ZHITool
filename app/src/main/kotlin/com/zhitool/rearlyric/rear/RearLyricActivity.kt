package com.zhitool.rearlyric.rear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zhitool.rearlyric.core.MediaControl
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.lyric.BatteryMode
import com.zhitool.rearlyric.lyric.CoverPosition
import com.zhitool.rearlyric.lyric.CoverShape
import com.zhitool.rearlyric.lyric.LyricBus
import com.zhitool.rearlyric.lyric.LyricColors
import com.zhitool.rearlyric.lyric.LyricFrameRate
import com.zhitool.rearlyric.lyric.LyricSource
import com.zhitool.rearlyric.lyric.LyricSourceState
import com.zhitool.rearlyric.lyric.LyricStyleMode
import com.zhitool.rearlyric.lyric.LyricStyleState
import com.zhitool.rearlyric.lyric.PackageStyleState
import com.zhitool.rearlyric.lyric.RearBackground
import com.zhitool.rearlyric.lyric.RearConfigState
import com.zhitool.rearlyric.lyric.RhythmDecay
import com.zhitool.rearlyric.lyric.StaggerConfigState
import com.zhitool.rearlyric.lyric.TextColorMode
import com.zhitool.rearlyric.tools.audio.AudioVisualizer
import com.zhitool.rearlyric.tools.charge.LiquidFillView
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
    // 歌词样式分支：错位交替走独立的纯净渲染（星空 + 两句歌词），不组合默认样式的任何界面元素。
    val lyricStyle by LyricStyleState.flow.collectAsState()
    if (lyricStyle == LyricStyleMode.STAGGER_ALTERNATE) {
        StaggerRearScreen()
        return
    }

    val baseCfg by RearConfigState.flow.collectAsState()
    val packageStyles by PackageStyleState.flow.collectAsState()
    val song by LyricBus.songFlow.collectAsState()
    val cover by LyricBus.cover.collectAsState()
    val playing by LyricBus.playingFlow.collectAsState()
    val playerPackage by LyricBus.playerPackage.collectAsState()
    val lyricSource by LyricSourceState.flow.collectAsState()
    val rearDisplayInfo by produceState(RearDisplayHelper.getRearDisplayInfo(), Unit) {
        value = withContext(Dispatchers.Default) { RearDisplayHelper.getRearDisplayInfo(forceRefresh = true) }
    }
    val cfg = packageStyles[playerPackage]?.config ?: baseCfg

    // 全量模式：长按封面 1 秒打开控制面板；展开态点大封面返回歌词。
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
    // 默认模式仍只有封面命中才启动长按；可视反馈则改由 Compose 与错位模式共用。
    var coverHolding by remember { mutableStateOf(false) }
    var coverHoldProgress by remember { mutableFloatStateOf(0f) }
    var coverHintVisible by remember { mutableStateOf(false) }
    var coverHintTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(coverHintTick) {
        if (coverHintTick == 0) return@LaunchedEffect
        coverHintVisible = true
        delay(REAR_PANEL_HINT_MS)
        coverHintVisible = false
    }
    // 面板空闲计时：任意触摸按下即暂停，抬起后从零重计；完全展开后才开始 5 秒倒计时。
    var panelInteractionTick by remember { mutableIntStateOf(0) }
    var panelTouchActive by remember { mutableStateOf(false) }
    // 控制面板「音量」按钮：点击后进度条变成音量条；1.5s 未触控自动还原成进度条。
    var volumeMode by remember { mutableStateOf(false) }
    var volumeTick by remember { mutableStateOf(0) }
    LaunchedEffect(coverPanelExpanded) {
        if (!coverPanelExpanded) {
            volumeMode = false
            panelTouchActive = false
        }
    }
    LaunchedEffect(volumeMode, volumeTick) { if (volumeMode) { delay(1500); volumeMode = false } }
    // 面板展开进度（0=收起，1=展开）：同时驱动 FullLyricView 的封面放大/歌词淡出、黑底淡入、按键升起。
    // 时长与 dock 放大动画接近，整体观感一致。
    val panelProgress by animateFloatAsState(
        targetValue = if (coverPanelExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
        label = "coverPanelProgress",
    )
    PanelAutoHideEffect(
        expanded = coverPanelExpanded,
        progress = panelProgress,
        interactionTick = panelInteractionTick,
        touchActive = panelTouchActive,
        onHide = { coverPanelExpanded = false },
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
    val context = LocalContext.current

    // 充电相关：自己观察电池（充电态 + 电量）——据此画歌词背后液体波浪 + 小锁位置电池，
    // 不再跳到独立充电页（充电页由 ChargeOverlay 在「未投放歌词」时才起）。
    var charging by remember { mutableStateOf(false) }
    var batteryLevel by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val battR = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                charging = plugged != 0 ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && s > 0) batteryLevel = l * 100 / s
            }
        }
        ContextCompat.registerReceiver(
            context, battR, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(battR) } }
    }
    // 液体波浪：「播放时充电动画」开 + 正在充电时显示，整体淡入淡出避免硬切。
    val chargeWaveAlpha by animateFloatAsState(
        targetValue = if (charging && cfg.chargeWave) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "chargeWaveAlpha",
    )
    // 液体颜色：封面取色背景时由封面取（液面提亮、底压暗，专门优化的渐变）；否则用淡一点的绿。
    val liquidTop: Int
    val liquidBottom: Int
    val waveSoftness: Float
    if (cfg.dynamicBackground) {
        liquidTop = coverColors.highlight.lighten(0.16f).toArgb()
        liquidBottom = coverColors.background.darken(0.34f).toArgb()
        waveSoftness = 0.5f
    } else {
        liquidTop = Color(0xFF7FE49B).toArgb()
        liquidBottom = Color(0xFF44C36C).toArgb()
        waveSoftness = 0.42f
    }
    // 电池：不显示 / 充电时 / 一直；充电未满才带闪电。
    val showBattery = when (cfg.batteryMode) {
        BatteryMode.NONE -> false
        BatteryMode.WHEN_CHARGING -> charging
        BatteryMode.ALWAYS -> true
    }

    // 三个独立律动开关（歌词发光/歌词律动/空间律动）：任一开启就持有内录（关闭全部则不启动）。
    // 先算"反应度"能量（× 低音-非低音增益，两团各自反应强度，背景与 UI 共用）；
    // 再分两路各乘独立强度：motion（歌词律动 + 空间律动的缩放，× 控件律动强度 uiRhythmIntensity）、
    // glow（歌词发光光晕，× 发光强度 lyricGlowIntensity），互不影响。
    val rhythmActive = cfg.lyricGlow || cfg.lyricRhythm || cfg.controlRhythm
    val rhythmEnergy = rememberRhythmEnergy(active = rhythmActive, decay = cfg.uiRhythmDecay)
    val percBase = rhythmEnergy.perc * (cfg.glowPercGain / 100f)
    val harmBase = rhythmEnergy.harm * (cfg.glowHarmGain / 100f)
    val uiGain = 0.6f + (cfg.uiRhythmIntensity / 100f).coerceIn(0f, 1f) * 1.8f
    val glowGain = (cfg.lyricGlowIntensity / 100f).coerceIn(0f, 3f)
    // motion 只在歌词律动或空间律动开时才有值；glow 只在歌词发光开时才有值（下游按各自开关再门控一次）。
    val motionActive = cfg.lyricRhythm || cfg.controlRhythm
    // 所有用户增益之后统一进软肩：普通歌 0..0.55 完全保留原手感，复杂/大音量段继续变化而不硬贴 1。
    val motionPerc = if (motionActive) softRhythmEnergy(percBase * uiGain) else 0f
    val motionHarm = if (motionActive) softRhythmEnergy(harmBase * uiGain) else 0f
    val glowPerc = if (cfg.lyricGlow) softRhythmEnergy(percBase * glowGain) else 0f
    val glowHarm = if (cfg.lyricGlow) softRhythmEnergy(harmBase * glowGain) else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(coverPanelExpanded) {
                if (coverPanelExpanded) {
                    observePanelTouches(
                        onPressedChange = { pressed -> panelTouchActive = pressed },
                        onInteraction = { panelInteractionTick++ },
                    )
                }
            },
    ) {
        RearBackgroundLayer(
            colors = bgColors,
            mode = cfg.background,
            intensity = cfg.rhythmIntensity,
            decay = cfg.rhythmDecay,
            spectrumHeight = cfg.spectrumHeight,
            percGain = cfg.glowPercGain / 100f,
            harmGain = cfg.glowHarmGain / 100f,
            // 选了律动/声谱即持有内录（投影一次、无感）；暂停/无声时内录是静音→柱子自然归零。
            active = cfg.background != RearBackground.DEFAULT,
        )

        // 播放时充电：歌词背后从下到上的柔化液体波浪（颜色随封面取色）。
        if (chargeWaveAlpha > 0.001f) {
            ChargeBackdrop(
                level = batteryLevel,
                alpha = chargeWaveAlpha * waveSoftness,
                topColor = liquidTop,
                bottomColor = liquidBottom,
                modifier = Modifier.fillMaxSize(),
            )
        }

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
                        onCoverHoldVisual = { holding, progress ->
                            coverHolding = holding
                            coverHoldProgress = progress
                            if (holding) coverHintVisible = false
                        }
                        onCoverShortTap = { coverHintTick++ }
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
                    view.setSingleLineMode(lyricSource == LyricSource.SUPERLYRIC)
                    view.setAudioEnergy(
                        glowPerc, glowHarm, motionPerc, motionHarm,
                        cfg.lyricGlow, cfg.lyricRhythm, cfg.controlRhythm,
                    )
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

        PanelLongPressHint(
            visible = !coverPanelExpanded && (coverHolding || coverHintVisible),
            progress = if (coverHolding) coverHoldProgress else 0f,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth(2f / 3f)
                .padding(bottom = 24.dp),
        )

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
            // 按钮属"空间律动"，只在 controlRhythm 开时脉动（歌词律动单开时按钮不动）。
            buttonPulse = if (cfg.controlRhythm) motionPerc else 0f,
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

        // 电池：画在小锁位置（左侧摄像头空隙、竖直居中）。iOS 式电池：数字 + 充电未满时闪电；
        // 长按解锁出锁徽标时让位（!lockVisible）。
        if (showBattery && !lockVisible) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewLeft = lyricLeftEdgeDp(maxWidth, cfg.safeAreaLeft)
                val battCenterX = viewLeft - 77.dp + (cfg.lockOffset * 2).dp
                val battW = 28.dp      // 长度 = 原 40 的 0.7（左缘不变，从右边缩）
                val battH = 15.2.dp    // 高度 = 原 19 的 0.8
                // 左缘按原 40dp 宽时的位置锚定（保持左侧不变，宽度缩小即从右边缩）；夹到屏内避免被裁。
                val battLeft = (battCenterX - 20.dp).coerceAtLeast(6.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = battLeft)
                        .size(width = battW, height = battH),
                ) {
                    BatteryBadge(
                        level = batteryLevel,
                        charging = charging,
                        showBolt = charging && batteryLevel < 100,
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
 * 「错位交替」样式的背屏页（纯净模式）：流动星空背景 + 当前句/上一句错落漂浮歌词 + 右上角时钟
 * （可开关）。样式配置走独立的 [StaggerConfigState]（与默认样式/包级配置不通用）；基础 [RearConfigState]
 * 只借用安全区微调/帧率/律动增益等设备级参数。严格长按整页 1 秒打开封面页（大封面 + 进度条 + 控制键），
 * 开合动画与切句同款"后退"语言：歌词舞台向中心收拢缩小压暗、封面从身后（1.12 倍）落到面前，
 * 星空同步收到后退脉冲。切句时歌词飞向随机远点、星空以该点为消失点收敛——歌词动星星才动。
 */
@Composable
private fun StaggerRearScreen() {
    val baseCfg by RearConfigState.flow.collectAsState()
    val staggerCfg by StaggerConfigState.flow.collectAsState()
    val song by LyricBus.songFlow.collectAsState()
    val cover by LyricBus.cover.collectAsState()
    val playing by LyricBus.playingFlow.collectAsState()
    val playerPackage by LyricBus.playerPackage.collectAsState()
    val lyricSource by LyricSourceState.flow.collectAsState()
    val rearDisplayInfo by produceState(RearDisplayHelper.getRearDisplayInfo(), Unit) {
        value = withContext(Dispatchers.Default) { RearDisplayHelper.getRearDisplayInfo(forceRefresh = true) }
    }

    val currentPosition by produceState(0L, baseCfg.frameRate, playing) {
        val frameIntervalNanos = when (baseCfg.frameRate) {
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

    // 歌词发光能量：链路与默认样式相同（root 内录 + 低音/非低音增益），开关与强度走星空独立配置。
    val rhythmEnergy = rememberRhythmEnergy(active = staggerCfg.lyricGlow, decay = baseCfg.uiRhythmDecay)
    val glowGain = (staggerCfg.lyricGlowIntensity / 100f).coerceIn(0f, 3f)
    val glowPerc = if (staggerCfg.lyricGlow) {
        softRhythmEnergy(rhythmEnergy.perc * (baseCfg.glowPercGain / 100f) * glowGain)
    } else {
        0f
    }
    val glowHarm = if (staggerCfg.lyricGlow) {
        softRhythmEnergy(rhythmEnergy.harm * (baseCfg.glowHarmGain / 100f) * glowGain)
    } else {
        0f
    }

    // 封面页（整页长按 1 秒打开）：面板进度驱动歌词舞台后退 + 黑底 + 封面/进度条/按键浮现。
    var coverPanelExpanded by remember { mutableStateOf(false) }
    val panelProgress by animateFloatAsState(
        targetValue = if (coverPanelExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
        label = "staggerPanelProgress",
    )
    var volumeMode by remember { mutableStateOf(false) }
    var volumeTick by remember { mutableStateOf(0) }
    var panelInteractionTick by remember { mutableIntStateOf(0) }
    var panelTouchActive by remember { mutableStateOf(false) }
    LaunchedEffect(coverPanelExpanded) {
        if (!coverPanelExpanded) {
            volumeMode = false
            panelTouchActive = false
        }
    }
    LaunchedEffect(volumeMode, volumeTick) { if (volumeMode) { delay(1500); volumeMode = false } }
    PanelAutoHideEffect(
        expanded = coverPanelExpanded,
        progress = panelProgress,
        interactionTick = panelInteractionTick,
        touchActive = panelTouchActive,
        onHide = { coverPanelExpanded = false },
    )

    // 收起态整页手势：短按只提示，按满统一的 1000ms 才打开；提示胶囊背景同步显示真实进度。
    var pageHolding by remember { mutableStateOf(false) }
    var pageHintVisible by remember { mutableStateOf(false) }
    var pageHintTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageHintTick) {
        if (pageHintTick == 0) return@LaunchedEffect
        pageHintVisible = true
        delay(REAR_PANEL_HINT_MS)
        pageHintVisible = false
    }
    val pageHoldProgress by animateFloatAsState(
        targetValue = if (pageHolding) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (pageHolding) REAR_LONG_PRESS_MS.toInt() else 0,
            easing = LinearEasing,
        ),
        label = "staggerCoverHoldProgress",
    )

    // 星空联动：切句 / 开合封面页时发脉冲，其余时间星星只漂浮闪烁不动。
    // 开封面页=后退（歌词星星一起变远）；关封面页=前进（一起变近），不是再变远。
    val starMotion = remember { StarMotion() }
    var panelPulseArmed by remember { mutableStateOf(false) }
    LaunchedEffect(coverPanelExpanded) {
        if (!panelPulseArmed) {
            panelPulseArmed = true
        } else {
            starMotion.pulse(0.55f, 0.5f, 560L, forward = !coverPanelExpanded)
        }
    }

    val coverBitmap by produceState<ImageBitmap?>(null, cover) {
        value = cover?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
    }

    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(coverPanelExpanded) {
                if (!coverPanelExpanded) {
                    detectTapGestures(
                        onPress = {
                            pageHintVisible = false
                            pageHolding = true
                            val released = withTimeoutOrNull(REAR_LONG_PRESS_MS) { tryAwaitRelease() }
                            pageHolding = false
                            when (released) {
                                null -> coverPanelExpanded = true
                                true -> pageHintTick++
                                false -> Unit // 移出/被子控件消费：取消，不显示短按提示。
                            }
                        },
                    )
                }
            }
            .pointerInput(coverPanelExpanded) {
                if (coverPanelExpanded) {
                    observePanelTouches(
                        onPressedChange = { pressed -> panelTouchActive = pressed },
                        onInteraction = { panelInteractionTick++ },
                    )
                }
            },
    ) {
        StarFieldBackground(motion = starMotion, modifier = Modifier.fillMaxSize())

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 安全区与默认样式一致：视图占右侧内容区，避开左 1/3 摄像头模组与挖孔。
            val viewLeftEdge = lyricLeftEdgeDp(maxWidth, baseCfg.safeAreaLeft)
            val contentWidth = maxWidth - viewLeftEdge
            val padLeftPx = with(density) { 18.dp.roundToPx() }
            val padTopPx = rearDisplayInfo.cutout.top + with(density) { 22.dp.roundToPx() }
            val safeStepPx = with(density) { 1.dp.roundToPx() }
            val padRightPx = ((rearDisplayInfo.cutout.right / 2) + with(density) { (11 + 6).dp.roundToPx() } - (baseCfg.safeAreaRight + 6) * safeStepPx).coerceAtLeast(0)
            val padBottomPx = rearDisplayInfo.cutout.bottom + with(density) { 22.dp.roundToPx() }
            // 歌词视图 → 全屏坐标换算（星空消失点用全屏归一坐标）。
            val screenWPx = with(density) { maxWidth.toPx() }
            val screenHPx = with(density) { maxHeight.toPx() }
            val viewLeftPx = with(density) { viewLeftEdge.toPx() }
            AndroidView(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(contentWidth)
                    .fillMaxHeight(),
                factory = { ctx -> StaggerLyricView(ctx) },
                update = { view ->
                    view.setPadding(padLeftPx, padTopPx, padRightPx, padBottomPx)
                    view.onRecedePulse = { vx, vy, dur ->
                        starMotion.pulse((viewLeftPx + vx) / screenWPx, vy / screenHPx, dur)
                    }
                    view.setPanelProgress(panelProgress)
                    view.setAudioEnergy(glowPerc, glowHarm, staggerCfg.lyricGlow)
                    view.bind(
                        song = song,
                        positionMs = currentPosition,
                        config = staggerCfg,
                        playing = playing,
                        singleLine = lyricSource == LyricSource.SUPERLYRIC,
                    )
                },
            )
        }

        PanelLongPressHint(
            visible = !coverPanelExpanded && (pageHolding || pageHintVisible),
            progress = if (pageHolding) pageHoldProgress else 0f,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth(2f / 3f)
                .padding(bottom = 24.dp),
        )

        // 封面页暗底：压暗星空与后退中的歌词，封面/进度条/按键叠其上。
        if (panelProgress > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f * panelProgress))
            )
        }

        // 封面页主体：左圆形转动封面 + 右歌名/歌手（排版同默认样式引导卡），
        // 从身后（1.12 倍）落到面前 —— 与歌词切句同款后退语言。
        StaggerCoverPanel(
            progress = panelProgress,
            coverBitmap = coverBitmap,
            title = song?.name.orEmpty().ifBlank { "未知歌曲" },
            artist = song?.artist.orEmpty().ifBlank { "未知歌手" },
            playing = playing,
            onCoverClick = { coverPanelExpanded = false },
        )

        // 进度条层与控制按键层：复用默认样式的控制面板组件（进度/时长/seek/音量、切歌/暂停/收藏）。
        CoverPanelProgress(
            progress = panelProgress,
            positionMs = currentPosition,
            playerPackage = playerPackage,
            volumeMode = volumeMode,
            onVolumeInteract = { volumeTick++ },
        )
        CoverPanelControls(
            expanded = coverPanelExpanded,
            progress = panelProgress,
            playing = playing,
            playerPackage = playerPackage,
            onVolume = { volumeMode = true; volumeTick++ },
        )

        // 背屏通知覆盖层：歌词上方弹胶囊，点击就地铺满成胶囊页（基础设施，纯净模式保留）。
        com.zhitool.rearlyric.tools.notify.RearNotifyOverlay(modifier = Modifier.fillMaxSize())

        // 紧急关闭按钮：误投正面屏时可点（正常投背屏被摄像头模组遮挡）。
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
 * 星空封面页主体：排版同默认样式引导卡——左=**圆形转动封面**（定死，不随任何配置；16s/圈、
 * 播放中才转、暂停保持角度），右=歌名/歌手；进度条与控制键在下方（复用默认组件）。
 * 进场 = 从身后（1.12 倍、透明）非线性落到面前，退场反向——与歌词后退动画同一运动语言。
 */
@Composable
private fun StaggerCoverPanel(
    progress: Float,
    coverBitmap: ImageBitmap?,
    title: String,
    artist: String,
    playing: Boolean,
    onCoverClick: () -> Unit,
) {
    if (progress <= 0.001f) return
    // 圆形转动封面：匀速 16s/圈（与默认样式一致），播放中才转、暂停停转保持角度。
    var coverAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { frame ->
                if (last != 0L) {
                    coverAngle = (coverAngle + (frame - last) / 1e9f * (360f / COVER_ROTATE_SECONDS)) % 360f
                }
                last = frame
            }
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 与默认引导卡同构：内容区=右 2/3（避开左 1/3 摄像头），封面占内容宽约 0.27。
        val regionLeft = maxWidth / 3f
        val rowW = maxWidth * (2f / 3f) - 32.dp
        val coverSize = (rowW * 0.30f).coerceIn(70.dp, 116.dp).coerceAtMost(maxHeight * 0.40f)
        Row(
            modifier = Modifier
                .offset(x = regionLeft + 16.dp, y = maxHeight * 0.14f)
                .width(rowW)
                .graphicsLayer {
                    alpha = progress
                    val s = 1.12f - 0.12f * progress
                    scaleX = s
                    scaleY = s
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 展开态点大封面直接返回歌词；无封面时占位圆同样保留关闭入口。
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .clip(CircleShape)
                    .clickable(onClick = onCoverClick),
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = "关闭控制页",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = coverAngle },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.10f)),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 8f)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 8f)),
                )
            }
        }
    }
}

/** 两种歌词模式共用的长按提示：位置、尺寸与进度填充完全一致。 */
@Composable
private fun PanelLongPressHint(
    visible: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 150 else 320),
        label = "panelLongPressHintAlpha",
    )
    if (alpha <= 0.001f) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.59f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = progress.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(Color.White.copy(alpha = 0.36f)),
            )
            Text(
                text = REAR_PANEL_HINT_TEXT,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** 面板动画完全到 1 后才计时；任何触摸按下会暂停，抬起或下一次交互均从 5 秒重新开始。 */
@Composable
private fun PanelAutoHideEffect(
    expanded: Boolean,
    progress: Float,
    interactionTick: Int,
    touchActive: Boolean,
    onHide: () -> Unit,
) {
    val latestOnHide by rememberUpdatedState(onHide)
    val fullyExpanded = expanded && progress >= 0.999f
    LaunchedEffect(fullyExpanded, interactionTick, touchActive) {
        if (!fullyExpanded || touchActive) return@LaunchedEffect
        delay(REAR_PANEL_IDLE_HIDE_MS)
        latestOnHide()
    }
}

/** 在父层 Initial pass 旁观所有子控件触摸，不消费事件；用于统一暂停/重置面板空闲计时。 */
private suspend fun PointerInputScope.observePanelTouches(
    onPressedChange: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    awaitPointerEventScope {
        var pressed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val nowPressed = event.changes.any { it.pressed }
            if (nowPressed != pressed) {
                pressed = nowPressed
                onPressedChange(pressed)
                onInteraction()
            }
        }
    }
}

/** 星空封面页的圆形封面转速（秒/圈，与默认样式一致）。 */
private const val COVER_ROTATE_SECONDS = 16f

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
    var volFrac by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // 音量模式：实时跟随系统音量——进入时读一次，之后轮询；物理音量键或其它来源改音量时音量条也同步移动。
    // 检测到外部变化时重置 1.5s 自动还原计时（onVolumeInteract），避免调到一半音量条消失；拖动中不打断手指。
    LaunchedEffect(volumeMode) {
        if (!volumeMode) return@LaunchedEffect
        var lastVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volFrac = lastVol.toFloat() / maxVol
        while (true) {
            val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (v != lastVol) {
                lastVol = v
                if (!dragging) {
                    volFrac = v.toFloat() / maxVol
                    onVolumeInteract()
                }
            }
            delay(120)
        }
    }
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
/** 颜色提亮：向白色插值（充电液面用）。 */
private fun Color.lighten(f: Float): Color =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, 1f)

/** 颜色压暗：向黑色插值（充电液体底部用）。 */
private fun Color.darken(f: Float): Color =
    Color(red * (1f - f), green * (1f - f), blue * (1f - f), 1f)

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
    /** 控件律动能量（0..1，已按用户增益换算）：驱动按钮随鼓点轻微缩放；0=不缩放（关闭/无声）。 */
    buttonPulse: Float = 0f,
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
                    pulse = buttonPulse,
                ) {
                    Icon(
                        imageVector = if (media.isFavorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (media.isFavorited) Color(0xFFFF4D6A) else Color.White,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
            PanelButton(onClick = { MediaControl.previous(context, playerPackage) }, pulse = buttonPulse) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            PanelButton(onClick = { MediaControl.playPause(context, playerPackage) }, pulse = buttonPulse) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            PanelButton(onClick = { MediaControl.next(context, playerPackage) }, pulse = buttonPulse) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            // 音量：点击后下方进度条变为音量调整条（1.5s 未触控自动还原成进度条）。返回歌词改为点封面。
            PanelButton(onClick = { onVolume() }, pulse = buttonPulse) {
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

/** 控制面板上统一尺寸的圆形按钮（内容可为图标或"词"文字）；[pulse]（0..1）随音乐轻微放大，不影响点击区域。 */
@Composable
private fun PanelButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pulse: Float = 0f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .graphicsLayer {
                val s = 1f + pulse.coerceIn(0f, 1f) * AUDIO_BUTTON_PULSE
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 控件律动：面板按钮随鼓点能量放大的最大幅度（比例）。 */
private const val AUDIO_BUTTON_PULSE = 0.22f

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

/** 歌词发光/控件律动读取的平滑能量（0..1，攻击用固定的 [UI_RHYTHM_ATTACK]、回落按 [RhythmDecay]，未乘用户增益）。 */
private class RhythmEnergy {
    var perc by mutableFloatStateOf(0f)
    var harm by mutableFloatStateOf(0f)
}

/**
 * 歌词发光 + 控件随音乐律动的音频能量源：与 [RearBackgroundLayer] **各自独立**持有 root 内录
 * （[AudioVisualizer] 内部按引用计数支持多方各自开关，互不影响）——这样背景模式=默认时也能单独
 * 开启歌词发光/控件律动，反之亦然。[active] 关闭时立即回落到 0（不残留旧能量）。
 */
@Composable
private fun rememberRhythmEnergy(active: Boolean, decay: RhythmDecay): RhythmEnergy {
    val context = LocalContext.current
    val energy = remember { RhythmEnergy() }
    DisposableEffect(active) {
        if (active) AudioVisualizer.startCapture(context)
        onDispose { if (active) AudioVisualizer.stopCapture(context) }
    }
    val decayFactor = rememberUpdatedState(uiDecayFactorOf(decay))
    LaunchedEffect(active) {
        if (!active) {
            energy.perc = 0f
            energy.harm = 0f
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val fall = decayFactor.value
                val grp = AudioVisualizer.groups
                // 攻击系数用比背景律动（ATTACK_BANDS=0.55）更平滑的 UI_RHYTHM_ATTACK：背景是大面积
                // 模糊光斑，快起快落看着"弹"；这里驱动的是文字/封面/按钮的**缩放**，逐帧原始能量的
                // 高频抖动会被放大成肉眼可见的"颤"，故起落都要更柔和才站得住。
                val tp = grp.getOrElse(0) { 0f }
                energy.perc += (tp - energy.perc) * (if (tp > energy.perc) UI_RHYTHM_ATTACK else fall)
                val th = grp.getOrElse(1) { 0f }
                energy.harm += (th - energy.harm) * (if (th > energy.harm) UI_RHYTHM_ATTACK else fall)
            }
        }
    }
    return energy
}

/**
 * 背屏背景层（取代旧 AnimatedCoverBackground）：默认=封面取色渐变 + 缓慢游走的高光团；
 * [RearBackground.PULSE]=高光团亮度/半径随音乐能量脉动 + 一团中心脉冲；
 * [RearBackground.SPECTRUM]=默认背景之上叠底部频谱柱（柱色跟随封面取色，半透明不挡歌词）。
 *
 * 音频数据来自 [AudioVisualizer]：[active] 期间引用计数持有 Visualizer；回调线程写裸值、
 * 这里每帧读出做攻击/衰减平滑（快起慢落，律动更"弹"），[level] 状态每帧变化驱动 Canvas 重绘。
 */
@Composable
private fun RearBackgroundLayer(
    colors: LyricColors,
    mode: RearBackground,
    intensity: Int,
    decay: RhythmDecay,
    spectrumHeight: Int,
    percGain: Float,
    harmGain: Float,
    active: Boolean,
) {
    val context = LocalContext.current
    val transition = rememberInfiniteTransition(label = "bg")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse),
        label = "drift",
    )
    // 三段高光在背景里持续流动的两个相位（线性、无缝循环，周期不同→各团轨迹不同步）。
    val phaseA by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(17000, easing = LinearEasing), RepeatMode.Restart), label = "pa",
    )
    val phaseB by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Restart), label = "pb",
    )
    // 换歌时封面取色变化做渐变过渡，避免背景颜色硬切。
    val background by animateColorAsState(colors.background, tween(900, easing = FastOutSlowInEasing), label = "bg0")
    val backgroundEnd by animateColorAsState(colors.backgroundEnd, tween(900, easing = FastOutSlowInEasing), label = "bg1")
    val highlight by animateColorAsState(colors.highlight, tween(900, easing = FastOutSlowInEasing), label = "hi")
    // 两团高光取色（只在换歌即颜色变化时重算）：两个不同色相的亮色。
    val glowColors = remember(colors) { assignGlowColors(colors.highlightGradient, colors.highlight) }

    // active 期间持有 root 内录（audio policy loopback，能拿到 offload 音乐、不静音、无投影提示）；离开/切回默认即停。
    // 暂停/无声时内录读到的就是静音 → 柱子自然归零，无需额外按播放态启停。
    DisposableEffect(active) {
        if (active) AudioVisualizer.startCapture(context)
        onDispose { if (active) AudioVisualizer.stopCapture(context) }
    }

    // 平滑后的频段(声谱用) + HPSS 三团(律动用)；frame 每帧自增驱动 Canvas 重绘。
    val bands = remember { FloatArray(AudioVisualizer.BAND_COUNT) }
    val grp = remember { FloatArray(2) }
    var frame by remember { mutableIntStateOf(0) }
    // 回落系数随配置实时变化（不重启帧循环）：起拍固定即时跟手，只有回落用它控制平滑快慢。
    val decayFactor = rememberUpdatedState(decayFactorOf(decay))
    LaunchedEffect(active) {
        if (!active) {
            bands.fill(0f)
            grp.fill(0f)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val fall = decayFactor.value
                val raw = AudioVisualizer.bands
                for (i in bands.indices) {
                    val t = raw.getOrElse(i) { 0f }
                    bands[i] += (t - bands[i]) * (if (t > bands[i]) ATTACK_BANDS else fall)
                }
                val rg = AudioVisualizer.groups
                for (i in grp.indices) {
                    val t = rg.getOrElse(i) { 0f }
                    grp[i] += (t - grp[i]) * (if (t > grp[i]) ATTACK_BANDS else fall)
                }
                frame++
            }
        }
    }

    // 强度增益：0..100 映射约 0.6..2.4，越大波动越夸张。
    val gain = 0.6f + (intensity / 100f).coerceIn(0f, 1f) * 1.8f

    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") frame // 每帧重绘触发（同时重读 bands 数组）
        drawRect(Brush.verticalGradient(listOf(background, backgroundEnd)))

        when (mode) {
            RearBackground.DEFAULT -> drawDriftHighlight(highlight, 0.20f, drift)

            RearBackground.SPECTRUM -> {
                drawDriftHighlight(highlight, 0.20f, drift)
                drawSpectrum(bands, gain, highlight, colors.highlightGradient, spectrumHeight / 100f)
            }

            RearBackground.PULSE -> {
                // 与默认背景共用封面底色，但静态高光略暗；静音/小音量时也不会退成近乎纯黑。
                drawDriftHighlight(highlight, 0.14f, drift)
                // 两团高光由 HPSS（谐波-打击分离）驱动，各取封面一色（不同色，不分深浅）、随能量明暗、在背景里流动：
                //  · 动次打次(打击分量) → 大团（像第一版那么大）
                //  · 人声/其它(谐波分量) → 正常大小
                val percE = softRhythmEnergy(grp[0] * gain * percGain)
                val harmE = softRhythmEnergy(grp[1] * gain * harmGain)
                val a = phaseA * (2f * PI.toFloat())
                val b = phaseB * (2f * PI.toFloat())
                drawBandGlow(glowColors[0], percE, 0.46f + 0.22f * cos(a), 0.58f + 0.16f * sin(b), 0.50f, 0.32f)        // 动次打次:大团
                drawBandGlow(glowColors[1], harmE, 0.60f + 0.24f * cos(b + 2.5f), 0.34f + 0.16f * sin(a + 1.5f), 0.20f, 0.26f) // 人声/其它:正常
            }
        }
    }
}

/** 缓慢游走的单团高光（默认/声谱底色用）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDriftHighlight(color: Color, alpha: Float, drift: Float) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width * (0.2f + 0.6f * drift), size.height * (0.8f - 0.6f * drift)),
            radius = size.maxDimension * 0.7f,
        )
    )
}

/**
 * 单个频段高光团：[energy] 控制亮度/半径，[cxFrac]/[cyFrac] 为当前流动中心（屏幕比例）；
 * 半径 = 屏幕最大边 × ([baseR] + e×[gainR])，由调用方按团大小指定（动次大团 / 谐波正常）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBandGlow(
    color: Color,
    energy: Float,
    cxFrac: Float,
    cyFrac: Float,
    baseR: Float,
    gainR: Float,
) {
    val e = energy.coerceIn(0f, 1f)
    // 软肩会为强段留出余量；同步提高发光斜率，使普通与强段都比旧两团更亮。
    val alpha = 0.05f + e * 0.72f
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width * cxFrac, size.height * cyFrac),
            radius = size.maxDimension * (baseR + e * gainR),
        )
    )
}

/**
 * 两团高光配色：从封面渐变色挑 **2 个色相不同**的色（动次打次 / 人声其它），不分深浅、只求不同。
 * 候选不足时用主色相 +150° 补足。每色略提亮以适合做发光团。
 */
private fun assignGlowColors(gradient: List<Color>, highlight: Color): List<Color> {
    fun hsvOf(c: Color): FloatArray {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        return hsv
    }
    // 候选：有一定亮度，按"亮且饱和"排序优先。
    val brights = gradient
        .filter { hsvOf(it)[2] >= 0.22f }
        .sortedByDescending { hsvOf(it).let { h -> h[2] * (0.5f + h[1]) } }
    val picked = ArrayList<Color>(2)
    for (c in brights) {
        val hue = hsvOf(c)[0]
        if (picked.none { hueDistance(hsvOf(it)[0], hue) < 30f }) picked.add(c)
        if (picked.size == 2) break
    }
    if (picked.isEmpty()) picked.add(highlight)
    if (picked.size < 2) {
        val bh = hsvOf(picked[0])
        picked.add(Color(android.graphics.Color.HSVToColor(floatArrayOf((bh[0] + 150f) % 360f, max(0.6f, bh[1]), max(0.7f, bh[2])))))
    }
    return picked.map { it.lighten(0.1f) }
}

/** 两个色相（0..360）的环形最小夹角。 */
private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** 起拍平滑系数（固定、即时跟手）：频段上行时向目标快速逼近，保证律动/频谱跟得上节拍。 */
private const val ATTACK_BANDS = 0.55f

/** 歌词发光/控件律动专用的攻击系数（比 [ATTACK_BANDS] 更柔和，回落仍用用户配置的 [RhythmDecay]；
 * 避免缩放/发光半径逐帧跟着原始能量的高频抖动而"颤"）。 */
private const val UI_RHYTHM_ATTACK = 0.16f

/** 律动恢复速度→每帧回落系数（越小回落越慢越柔和）。极快→极慢五档。 */
private fun decayFactorOf(decay: RhythmDecay): Float = when (decay) {
    RhythmDecay.VERY_FAST -> 0.5f
    RhythmDecay.FAST -> 0.3f
    RhythmDecay.MEDIUM -> 0.16f
    RhythmDecay.SLOW -> 0.085f
    RhythmDecay.VERY_SLOW -> 0.04f
}

/**
 * 控件/歌词律动专用的回落系数：同名五档整体比 [decayFactorOf] 慢两级（"中"=背景的"极慢"），
 * 用户反馈背景那套"极慢"给歌词/控件用还是不够慢。继续按大致减半的节奏往下延伸两档。
 */
private fun uiDecayFactorOf(decay: RhythmDecay): Float = when (decay) {
    RhythmDecay.VERY_FAST -> 0.16f
    RhythmDecay.FAST -> 0.085f
    RhythmDecay.MEDIUM -> 0.04f
    RhythmDecay.SLOW -> 0.02f
    RhythmDecay.VERY_SLOW -> 0.01f
}

/**
 * 底部频谱柱（背景装饰，画在最底层=歌词后面）：从屏幕**横向中心向左右两侧镜像**展开，
 * 低频在中央、高频向两侧；从底向上、半透明，柱色沿封面渐变色取（中心→边缘）。
 * 高度不设固定上限，由 [heightScale]（=声谱高度%/100）缩放，封顶屏幕高度。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpectrum(
    bands: FloatArray,
    gain: Float,
    highlight: Color,
    gradient: List<Color>,
    heightScale: Float,
) {
    val n = bands.size
    if (n == 0) return
    val w = size.width
    val h = size.height
    val centerX = w / 2f
    val slot = (w / 2f) / n        // 每侧 n 条
    val barW = slot * 0.52f        // 比上一版略粗一点点
    val cr = CornerRadius(barW / 2f, barW / 2f)
    for (i in 0 until n) {
        val v = (bands[i] * gain).coerceIn(0f, 1f)
        // 不限高：柱高=能量×屏高×缩放，最高到满屏。
        val barH = (v * h * heightScale).coerceAtMost(h)
        if (barH < 1.5f) continue
        val color = gradientColorAt(gradient, highlight, if (n == 1) 0f else i.toFloat() / (n - 1))
            .copy(alpha = 0.20f + v * 0.28f)
        val inset = (slot - barW) / 2f
        // 中心向右第 i 条 + 镜像到左侧，关于中心对称。
        drawRoundRect(color, Offset(centerX + i * slot + inset, h - barH), Size(barW, barH), cr)
        drawRoundRect(color, Offset(centerX - (i + 1) * slot + inset, h - barH), Size(barW, barH), cr)
    }
}

/** 沿渐变色组取 [t]∈0..1 处颜色；空组回退 [fallback]。 */
private fun gradientColorAt(colors: List<Color>, fallback: Color, t: Float): Color {
    if (colors.isEmpty()) return fallback
    if (colors.size == 1) return colors[0]
    val scaled = t.coerceIn(0f, 1f) * (colors.size - 1)
    val idx = scaled.toInt().coerceIn(0, colors.size - 2)
    return lerp(colors[idx], colors[idx + 1], scaled - idx)
}

/**
 * 充电液体波浪层（播放时充电）：歌词背后从底部上升的柔化液体（复用 [LiquidFillView]，竖向渐变色由
 * [topColor]/[bottomColor] 指定——封面取色时随封面）。整体由 [alpha] 控制淡入淡出 + 压低不透明度，
 * 不盖住歌词。电量数字改由 [BatteryBadge] 在小锁位置呈现，这里不再画。
 */
@Composable
private fun ChargeBackdrop(level: Int, alpha: Float, topColor: Int, bottomColor: Int, modifier: Modifier = Modifier) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(level) { fill.animateTo((level / 100f).coerceIn(0f, 1f), tween(1500)) }
    AndroidView(
        modifier = modifier.alpha(alpha.coerceIn(0f, 1f)),
        factory = { LiquidFillView(it) },
        update = {
            it.setLiquidColors(topColor, bottomColor)
            it.setFillLevel(fill.value)
        },
    )
}

/**
 * iOS 式电池徽标（画在小锁位置）：圆角电池壳 + 从左按电量填充（充电=绿、否则=白）+ 正极小凸点；
 * 左侧白色加粗电量数字；[showBolt] 时右侧叠充电闪电。充满（100）由调用方令 showBolt=false → 只显示数字。
 */
@Composable
private fun BatteryBadge(level: Int, charging: Boolean, showBolt: Boolean, modifier: Modifier = Modifier) {
    val numberPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.LEFT
            setShadowLayer(5f, 0f, 1f, android.graphics.Color.argb(150, 0, 0, 0))
        }
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val nubW = w * 0.05f
        val bodyW = w - nubW
        val corner = h * 0.34f
        // 电池外壳（空电浅底）。
        drawRoundRect(
            color = Color.White.copy(alpha = 0.20f),
            size = Size(bodyW, h),
            cornerRadius = CornerRadius(corner, corner),
        )
        // 按电量从左填充：充电=绿、否则=白；裁到外壳圆角，右缘平直。
        val frac = (level / 100f).coerceIn(0f, 1f)
        val fillColor = if (charging) Color(0xFF34C759) else Color.White.copy(alpha = 0.85f)
        val clip = Path().apply {
            addRoundRect(RoundRect(0f, 0f, bodyW, h, CornerRadius(corner, corner)))
        }
        clipPath(clip) {
            drawRect(color = fillColor, size = Size(bodyW * frac, h))
        }
        // 正极小凸点。
        val nubH = h * 0.36f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.5f),
            topLeft = Offset(bodyW + nubW * 0.05f, (h - nubH) / 2f),
            size = Size(nubW * 0.85f, nubH),
            cornerRadius = CornerRadius(nubW * 0.4f, nubW * 0.4f),
        )
        // 数字 + 闪电作为一个整体居中排在整块电池上（紧挨、无间距），而非数字左 / 闪电右。
        numberPaint.textSize = h * 0.66f
        val fm = numberPaint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        val text = "$level"
        val tw = numberPaint.measureText(text)
        val bh = h * 0.64f
        val bw = bh * 0.6f
        val gap = if (showBolt) h * 0.03f else 0f
        val groupW = tw + if (showBolt) gap + bw else 0f
        val startX = (bodyW - groupW) / 2f
        drawIntoCanvas { it.nativeCanvas.drawText(text, startX, baseline, numberPaint) }
        if (showBolt) {
            val left = startX + tw + gap
            val top = (h - bh) / 2f
            val bolt = Path().apply {
                moveTo(left + bw * 0.62f, top)
                lineTo(left + bw * 0.16f, top + bh * 0.58f)
                lineTo(left + bw * 0.46f, top + bh * 0.58f)
                lineTo(left + bw * 0.38f, top + bh)
                lineTo(left + bw * 0.86f, top + bh * 0.40f)
                lineTo(left + bw * 0.54f, top + bh * 0.40f)
                close()
            }
            drawPath(bolt, color = Color.White)
        }
    }
}
