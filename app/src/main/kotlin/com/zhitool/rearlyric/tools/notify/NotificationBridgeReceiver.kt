/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.zhitool.rearlyric.lyric.ToolProjectionState
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlin.concurrent.thread

/** Receives sanitized notifications captured by ZHITool's LSPosed code inside SystemUI. */
class NotificationBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationBridge.ACTION_POSTED) return
        val cfg = NotifyConfigState.current
        if (!cfg.enabled || !ToolProjectionState.current) return

        val pkg = intent.getStringExtra(NotificationBridge.EXTRA_PACKAGE) ?: return
        if (pkg == context.packageName || pkg !in cfg.selectedApps) return
        if (cfg.followDnd) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager != null && manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        }

        val rawTitle = intent.getStringExtra(NotificationBridge.EXTRA_TITLE).orEmpty()
        val rawText = intent.getStringExtra(NotificationBridge.EXTRA_TEXT).orEmpty()
        val payload = NotifyPayload(
            pkg = pkg,
            title = if (cfg.hideTitle) "ZHITool" else rawTitle,
            text = if (cfg.hideContent) "[新消息]" else rawText,
            autoSec = cfg.autoDestroySeconds,
            notificationKey = intent.getStringExtra(NotificationBridge.EXTRA_KEY),
            clickToken = intent.getStringExtra(NotificationBridge.EXTRA_CLICK_TOKEN),
            openable = intent.getBooleanExtra(NotificationBridge.EXTRA_OPENABLE, false),
        )

        if (!cfg.onlyWhenUpsideDown) {
            dispatch(context.applicationContext, payload)
            return
        }

        val pending = goAsync()
        MainScreenCoverProbe.check(context.applicationContext) { covered ->
            try {
                if (covered) dispatch(context.applicationContext, payload)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dispatch(context: Context, payload: NotifyPayload) {
        NotifyBus.add(payload)
        when {
            NotifyBus.cardVisible -> Unit
            NotifyBus.pillHostActive -> NotifyBus.showPill(payload)
            else -> thread(name = "zhi-notify-project") {
                RearOverlaySupport.project("am start -n $ACTIVITY", "RearNotificationActivity")
            }
        }
    }

    companion object {
        private const val ACTIVITY = "com.zhitool.rearlyric/.tools.notify.RearNotificationActivity"
    }
}

/** One-shot main-screen proximity read, preserving the old "only while face-down" behavior. */
private object MainScreenCoverProbe {
    private const val TIMEOUT_MS = 800L
    private val handler = Handler(Looper.getMainLooper())

    fun check(context: Context, result: (Boolean) -> Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getSensorList(Sensor.TYPE_ALL)
            ?.firstOrNull { it.name.contains("Proximity") && !it.name.contains("Back") }
            ?: manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (manager == null || sensor == null) {
            result(false)
            return
        }

        var completed = false
        var listener: SensorEventListener? = null
        lateinit var timeout: Runnable
        fun complete(covered: Boolean) {
            if (completed) return
            completed = true
            listener?.let(manager::unregisterListener)
            handler.removeCallbacks(timeout)
            result(covered)
        }

        timeout = Runnable { complete(false) }
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor == sensor) complete(event.values[0] < sensor.maximumRange * 0.2f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            complete(false)
            return
        }
        handler.postDelayed(timeout, TIMEOUT_MS)
    }
}
