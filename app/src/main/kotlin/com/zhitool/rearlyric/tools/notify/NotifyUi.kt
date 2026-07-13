/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.zhitool.rearlyric.rear.RearDisplayHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

class AppMeta(val icon: ImageBitmap?, val name: String)

@Composable
fun rememberAppMeta(pkg: String): AppMeta {
    val context = LocalContext.current
    val meta by produceState(AppMeta(null, pkg), pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                AppMeta(pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap(), pm.getApplicationLabel(ai).toString())
            }.getOrElse { AppMeta(null, pkg) }
        }
    }
    return meta
}

private val CARD_GRADIENT = listOf(Color(0xFF15171C), Color(0xFF0B0C0E))
private val CARD_COLOR = Color(0xFF1F2024).copy(alpha = 0.94f)

/**
 * 通知卡片堆叠：折叠态最新卡在前、其余露边堆叠，点击展开后可上下滑动浏览。
 * 右下角"打开通知"胶囊与点击卡片均执行该通知原始 PendingIntent。计时到点回调 [onEmpty]。
 */
@Composable
fun NotifyCardStack(
    items: List<NotifyPayload>,
    onOpenFront: (NotifyPayload) -> Unit,
    onEmpty: () -> Unit,
    animateEntrance: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var listExpanded by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val baseSec = items.firstOrNull()?.autoSec ?: 5

    LaunchedEffect(items.size) { lastInteraction = System.currentTimeMillis() }
    LaunchedEffect(lastInteraction, listExpanded) {
        if (listExpanded) return@LaunchedEffect // 展开浏览时不自动消失
        val sec = baseSec.coerceIn(1, 3600) * if (items.size > 1) 2 else 1
        delay(sec * 1000L)
        onEmpty()
    }

    val capEnter = remember { Animatable(if (animateEntrance) 0f else 1f) }
    val contentReveal = remember { Animatable(if (animateEntrance) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animateEntrance) return@LaunchedEffect
        capEnter.animateTo(1f, tween(440, easing = FastOutSlowInEasing))
        delay(220)
        contentReveal.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }
    val capHidePx = with(density) { 160.dp.toPx() }

    Box(modifier) {
        if (!listExpanded) {
            CollapsedStack(
                items = items,
                capEnter = capEnter.value,
                contentReveal = contentReveal.value,
                capHidePx = capHidePx,
                modifier = Modifier.align(Alignment.Center),
                onTap = {
                    if (items.size > 1) listExpanded = true
                    else items.lastOrNull()?.takeIf { it.openable }?.let(onOpenFront)
                    lastInteraction = System.currentTimeMillis()
                },
            )
        } else {
            val scroll = rememberScrollState()
            LaunchedEffect(scroll) {
                snapshotFlow { scroll.value }.drop(1).collect { lastInteraction = System.currentTimeMillis() }
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.Center,
            ) {
                items.asReversed().forEach { card ->
                    NotificationCard(
                        card = card,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(enabled = card.openable) { onOpenFront(card) },
                    )
                }
            }
        }
        items.lastOrNull()?.takeIf { it.openable }?.let { latest ->
            FrontOpenPill(
                modifier = Modifier.align(Alignment.BottomEnd),
                onClick = { onOpenFront(latest) },
            )
        }
    }
}

/** 折叠态：只显示最新一条；其余条数用下方小圆点的数字表示。点击展开。 */
@Composable
private fun CollapsedStack(
    items: List<NotifyPayload>,
    capEnter: Float,
    contentReveal: Float,
    capHidePx: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val more = items.size - 1
    Column(
        modifier = modifier.fillMaxWidth().clickable(
            enabled = items.size > 1 || items.lastOrNull()?.openable == true,
            onClick = onTap,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NotificationCard(
            card = items.last(),
            capOffsetY = (1f - capEnter) * capHidePx,
            capAlpha = capEnter,
            contentReveal = contentReveal,
            modifier = Modifier.fillMaxWidth(),
        )
        if (more > 0) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = contentReveal }
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(CARD_COLOR),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "$more", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NotificationCard(
    card: NotifyPayload,
    modifier: Modifier = Modifier,
    capOffsetY: Float = 0f,
    capAlpha: Float = 1f,
    contentReveal: Float = 1f,
) {
    val density = LocalDensity.current
    val meta = rememberAppMeta(card.pkg)
    val contentShiftPx = with(density) { 24.dp.toPx() }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .graphicsLayer { translationY = capOffsetY; alpha = capAlpha }
                .clip(RoundedCornerShape(24.dp))
                .background(CARD_COLOR)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            meta.icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = meta.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = contentReveal
                    translationY = (1f - contentReveal) * contentShiftPx
                }
                .clip(RoundedCornerShape(18.dp))
                .background(CARD_COLOR)
                .padding(16.dp),
        ) {
            if (card.title.isNotBlank()) {
                Text(
                    text = card.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.text.isNotBlank()) {
                Text(
                    text = card.text,
                    color = Color(0xFFD2D2D6),
                    fontSize = 14.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (card.title.isNotBlank()) 4.dp else 0.dp),
                )
            }
        }
    }
}

@Composable
private fun FrontOpenPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .padding(bottom = 4.dp, end = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2A82E4).copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "打开通知 ↗", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private enum class OverlayMode { HIDDEN, PILL, PAGE }

/**
 * 歌词/充电页上方的通知覆盖层：先以胶囊浮出（图标 + N 条新通知），3s 自动上滑收起，
 * 也可手动上滑提前收起；点击胶囊则**就地**把黑色背景铺满整页变成"胶囊页"（不跳转新页面）。
 */
@Composable
fun RearNotifyOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pill by NotifyBus.pill.collectAsState()
    val items by NotifyBus.items.collectAsState()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(OverlayMode.HIDDEN) }
    val pillAnim = remember { Animatable(0f) } // 0 = 收在上方, 1 = 落下
    val expand = remember { Animatable(0f) }   // 0 = 胶囊, 1 = 铺满整页

    LaunchedEffect(pill?.id) {
        val p = pill ?: return@LaunchedEffect
        if (mode == OverlayMode.PAGE) return@LaunchedEffect // 已展开：静默并入堆叠
        mode = OverlayMode.PILL
        pillAnim.snapTo(0f)
        pillAnim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        delay(3000)
        if (mode == OverlayMode.PILL) {
            pillAnim.animateTo(0f, tween(260))
            mode = OverlayMode.HIDDEN
            if (NotifyBus.pill.value?.id == p.id) NotifyBus.clearPill()
        }
    }

    if (mode == OverlayMode.HIDDEN) return

    val dismiss: () -> Unit = {
        scope.launch {
            if (expand.value > 0f) expand.animateTo(0f, tween(280)) else pillAnim.animateTo(0f, tween(220))
            mode = OverlayMode.HIDDEN
            NotifyBus.clearPill()
            NotifyBus.clearItems()
        }
    }

    val rearInfo by produceState(RearDisplayHelper.getRearDisplayInfo(), Unit) {
        value = withContext(Dispatchers.Default) { RearDisplayHelper.getRearDisplayInfo(forceRefresh = true) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentWidth = maxWidth * (2f / 3f)
        val safeTop = with(density) { rearInfo.cutout.top.toDp() + 22.dp }
        val safePadding = with(density) {
            PaddingValues(
                top = safeTop,
                end = (rearInfo.cutout.right.toDp() / 2f) + 11.dp,
                bottom = rearInfo.cutout.bottom.toDp() + 22.dp,
            )
        }

        // 胶囊页黑色背景：从胶囊位置为圆心**蔓延铺满**（圆形展开）。
        if (expand.value > 0f) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val centerXpx = wPx * (2f / 3f) // 右 2/3 的中心
            val centerYpx = with(density) { (safeTop + 6.dp + 20.dp).toPx() } // 胶囊中心
            val radius = hypot(wPx, hPx) // 覆盖到最远角
            Box(
                modifier = Modifier
                    .offset { IntOffset((centerXpx - radius).roundToInt(), (centerYpx - radius).roundToInt()) }
                    .size(with(density) { (radius * 2f).toDp() })
                    .graphicsLayer { scaleX = expand.value; scaleY = expand.value }
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(CARD_GRADIENT)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(contentWidth)
                    .fillMaxHeight()
                    .padding(safePadding)
                    .graphicsLayer { alpha = expand.value },
            ) {
                NotifyCardStack(
                    items = items,
                    onOpenFront = { NotifyActions.openOnFront(context, it); dismiss() },
                    onEmpty = dismiss,
                    animateEntrance = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 胶囊本体（展开中渐隐）。
        if (mode == OverlayMode.PILL || expand.value < 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(contentWidth)
                    .fillMaxHeight()
                    .padding(top = safeTop + 6.dp),
            ) {
                NotifyPillContent(
                    count = items.size,
                    pkg = (pill ?: items.lastOrNull())?.pkg,
                    alpha = pillAnim.value * (1f - expand.value),
                    offsetY = (pillAnim.value - 1f) * with(density) { 96.dp.toPx() },
                    modifier = Modifier.align(Alignment.TopCenter),
                    onTap = {
                        mode = OverlayMode.PAGE
                        NotifyBus.clearPill()
                        scope.launch { expand.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
                    },
                    onSwipeUp = dismiss,
                )
            }
        }
    }
}

@Composable
private fun NotifyPillContent(
    count: Int,
    pkg: String?,
    alpha: Float,
    offsetY: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    if (pkg == null || alpha <= 0.01f) return
    val density = LocalDensity.current
    val meta = rememberAppMeta(pkg)
    val swipeThreshold = with(density) { 36.dp.toPx() }
    Row(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha; translationY = offsetY }
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF101114).copy(alpha = 0.95f))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = { if (total < -swipeThreshold) onSwipeUp() },
                )
            }
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        meta.icon?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "$count 条新通知",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
