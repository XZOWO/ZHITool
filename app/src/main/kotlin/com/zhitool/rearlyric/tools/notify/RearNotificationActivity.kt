/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 背屏通知卡片页（背屏空闲时整页投放；占用时改由 RearNotifyOverlay 就地展开）。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zhitool.rearlyric.rear.RearDisplayHelper
import com.zhitool.rearlyric.rear.RearStage
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** 背屏空闲时整页投放的通知卡片页（占用时走 RearNotifyOverlay 的胶囊→就地展开）。 */
class RearNotificationActivity : ComponentActivity() {

    private val onRear = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RearOverlaySupport.applyLockScreenFlags(this)
        RearStage.enter(this)
        updateRearGate()
        setContent {
            if (onRear.value) {
                NotificationCardPage(
                    onExpired = { finish() },
                    onOpenFront = { notification -> NotifyActions.openOnFront(this, notification); finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RearOverlaySupport.applyLockScreenFlags(this)
        NotifyBus.cardVisible = true
        updateRearGate()
        if (RearOverlaySupport.currentDisplayId(this) != RearOverlaySupport.REAR_DISPLAY_ID) {
            val tid = taskId
            thread(name = "zhi-notify-heal") { RearOverlaySupport.selfHealToRear(tid) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRearGate()
    }

    private fun updateRearGate() {
        val rear = RearOverlaySupport.currentDisplayId(this) == RearOverlaySupport.REAR_DISPLAY_ID
        onRear.value = rear
        RearOverlaySupport.applyPlaceholderFlags(this, rear)
    }

    override fun onPause() {
        super.onPause()
        NotifyBus.cardVisible = false
    }

    override fun onDestroy() {
        RearStage.leave(this)
        NotifyBus.cardVisible = false
        NotifyBus.clearItems()
        super.onDestroy()
    }
}

@Composable
private fun NotificationCardPage(onExpired: () -> Unit, onOpenFront: (NotifyPayload) -> Unit) {
    val density = LocalDensity.current
    val items by NotifyBus.items.collectAsState()
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onExpired() }
        return
    }
    val rearInfo by produceState(RearDisplayHelper.getRearDisplayInfo(), Unit) {
        value = withContext(Dispatchers.Default) { RearDisplayHelper.getRearDisplayInfo(forceRefresh = true) }
    }
    val safePadding = with(density) {
        PaddingValues(
            top = rearInfo.cutout.top.toDp() + 22.dp,
            end = (rearInfo.cutout.right.toDp() / 2f) + 11.dp,
            bottom = rearInfo.cutout.bottom.toDp() + 22.dp,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF15171C), Color(0xFF0B0C0E)))),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val contentWidth = maxWidth * (2f / 3f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(contentWidth)
                    .fillMaxHeight()
                    .padding(safePadding),
            ) {
                NotifyCardStack(
                    items = items,
                    onOpenFront = onOpenFront,
                    onEmpty = onExpired,
                    animateEntrance = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
