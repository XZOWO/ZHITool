/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 充电动画 Activity 参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的
 * RearScreenChargingActivity（全屏液体 + 安全区居中电量）。Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.charge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.rear.RearDisplayHelper
import com.zhitool.rearlyric.rear.RearStage
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** 背屏充电动画：全屏上升绿色液体 + 安全区中央电量百分比。 */
class RearChargingActivity : ComponentActivity() {

    private val onRear = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RearOverlaySupport.applyLockScreenFlags(this)
        RearStage.enter(this)
        val level = intent.getIntExtra(EXTRA_LEVEL, 0).coerceIn(0, 100)
        val alwaysOn = intent.getBooleanExtra(EXTRA_ALWAYS_ON, false)
        updateRearGate()
        setContent { if (onRear.value) ChargingScreen(level, alwaysOn, onExpired = { finish() }) }
    }

    override fun onResume() {
        super.onResume()
        RearOverlaySupport.applyLockScreenFlags(this)
        com.zhitool.rearlyric.tools.notify.NotifyBus.pillHostResumed(this)
        updateRearGate()
        if (RearOverlaySupport.currentDisplayId(this) != RearOverlaySupport.REAR_DISPLAY_ID) {
            val tid = taskId
            thread(name = "zhi-charge-heal") { RearOverlaySupport.selfHealToRear(tid) }
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
        com.zhitool.rearlyric.tools.notify.NotifyBus.pillHostPaused(this)
    }

    override fun onDestroy() {
        RearStage.leave(this)
        com.zhitool.rearlyric.tools.notify.NotifyBus.pillHostPaused(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LEVEL = "batteryLevel"
        const val EXTRA_ALWAYS_ON = "alwaysOn"
        const val ACTION_FINISH = "com.zhitool.rearlyric.tools.charge.FINISH"
    }
}

@Composable
private fun ChargingScreen(initialLevel: Int, alwaysOn: Boolean, onExpired: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var level by remember { mutableIntStateOf(initialLevel) }

    // 拔电收尾（来自 RearToolsService）+ 实时电量更新。
    DisposableEffect(Unit) {
        val finishR = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == RearChargingActivity.ACTION_FINISH) onExpired()
            }
        }
        val battR = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && s > 0) level = l * 100 / s
            }
        }
        ContextCompat.registerReceiver(
            context, finishR, IntentFilter(RearChargingActivity.ACTION_FINISH), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context, battR, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(finishR) }
            runCatching { context.unregisterReceiver(battR) }
        }
    }

    if (!alwaysOn) LaunchedEffect(Unit) { delay(8000); onExpired() }

    val rearInfo by produceState(RearDisplayHelper.getRearDisplayInfo(), Unit) {
        value = withContext(Dispatchers.Default) { RearDisplayHelper.getRearDisplayInfo(forceRefresh = true) }
    }

    // 液体填充：首次 0→电量(2s 减速)，之后平滑跟随电量变化。
    val fill = remember { Animatable(0f) }
    LaunchedEffect(level) { fill.animateTo(level / 100f, tween(1500)) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { LiquidFillView(it) },
            update = { it.setFillLevel(fill.value) },
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 数字居中于背屏安全区（右 2/3，避开左 1/3 摄像头与挖孔），而非整屏中央。
            val contentWidth = maxWidth * (2f / 3f)
            val safePadding = with(density) {
                PaddingValues(
                    top = rearInfo.cutout.top.toDp() + 22.dp,
                    end = (rearInfo.cutout.right.toDp() / 2f) + 11.dp,
                    bottom = rearInfo.cutout.bottom.toDp() + 22.dp,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(contentWidth)
                    .fillMaxHeight()
                    .padding(safePadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$level%",
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // 背屏通知覆盖层：充电动画上方弹胶囊，点击就地铺满成胶囊页，不跳转。
        com.zhitool.rearlyric.tools.notify.RearNotifyOverlay(modifier = Modifier.fillMaxSize())
    }
}
