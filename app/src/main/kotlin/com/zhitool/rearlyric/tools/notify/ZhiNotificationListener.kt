/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 通知监听参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 NotificationService。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlin.concurrent.thread

/** 监听系统通知，把选中应用的通知投到背屏。 */
class ZhiNotificationListener : NotificationListenerService(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var mainProximity: Sensor? = null
    @Volatile private var mainCovered = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerMainProximity()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val cfg = NotifyConfigState.current
        if (!cfg.enabled) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return // 常驻通知
        if (sbn.packageName == packageName) return
        if (sbn.packageName !in cfg.selectedApps) return

        if (cfg.followDnd) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        }
        if (cfg.onlyWhenUpsideDown && !mainCovered) return

        var title = n.extras.getString(Notification.EXTRA_TITLE).orEmpty()
        var text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (cfg.hideTitle) title = "ZHITool"
        if (cfg.hideContent) text = "[新消息]"

        val payload = NotifyPayload(sbn.packageName, title, text, cfg.autoDestroySeconds)
        NotifyBus.add(payload)
        when {
            // 卡片页已在背屏：直接加入堆叠（上面已 add）。
            NotifyBus.cardVisible -> Unit
            // 歌词/充电页正显示在背屏：上方弹安卓原生式胶囊，不重绘当前页。
            // 用「正在前台的承载页」判断（不是 RearStage.occupied，避免残留 token 导致弹了无人渲染的胶囊）。
            NotifyBus.pillHostActive -> NotifyBus.showPill(payload)
            // 背屏空闲：整页投放通知卡片页。
            else -> thread(name = "zhi-notify-project") {
                RearOverlaySupport.project("am start -n $ACTIVITY", "RearNotificationActivity")
            }
        }
    }

    // ── 主屏接近传感器（倒扣检测） ──

    private fun registerMainProximity() {
        if (mainProximity != null) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm
        // 优先“非 Back”的接近传感器（主屏），否则用默认。
        val sensor = sm.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.name.contains("Proximity") && !it.name.contains("Back") }
            ?: sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            ?: return
        mainProximity = sensor
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = mainProximity ?: return
        if (event.sensor != sensor) return
        mainCovered = event.values[0] < sensor.maximumRange * 0.2f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val ACTIVITY = "com.zhitool.rearlyric/.tools.notify.RearNotificationActivity"
    }
}
