/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 保活/遮盖检测流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的
 * RearScreenKeeperService / AlwaysWakeUpService。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.core.ForegroundCoordinator
import com.zhitool.rearlyric.core.RearTools
import com.zhitool.rearlyric.lyric.LyricService
import com.zhitool.rearlyric.tools.charge.ChargeOverlay
import kotlin.concurrent.thread

/**
 * 背屏工具的前台保活服务，承载三件事：
 *  1. **未投放常亮**（[ToolsConfig.alwaysWakeUp]）：持续给背屏发 keycode 唤醒。
 *  2. **投放保活**：投放任意应用到背屏后保持背屏常亮 + 监控应用是否还在背屏（离开即收尾）。
 *  3. **背屏遮盖检测**（[ToolsConfig.coverDetection]）：背屏接近传感器被覆盖即把应用拉回主屏。
 *
 * 命令全走 [RearTools]（root）。所有 shell 调用在 [worker] 线程，不阻塞主线程。
 */
class RearToolsService : Service(), SensorEventListener {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = true
    @Volatile private var worker: Thread? = null

    /** 当前被保活的背屏应用（null = 仅未投放常亮）。 */
    @Volatile private var keeperApp: RearTools.AppTask? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    @Volatile private var covered = false
    private var coveredSince = 0L

    // 充电动画：运行时电源接收器（manifest 注册的 ACTION_POWER_CONNECTED 在 Android 8+ 不送达）。
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> ChargeOverlay.project(applicationContext)
                Intent.ACTION_POWER_DISCONNECTED -> ChargeOverlay.finish(applicationContext)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RUNNING = true
        ForegroundCoordinator.started(ForegroundCoordinator.TAG_TOOLS)
        startForeground(LyricService.SHARED_NOTIF_ID, LyricService.buildSharedNotification(this))
        acquireWakeLock()
        startWorker()
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 始终先保证前台，避免 startForegroundService 5s 限制崩溃。
        startForeground(LyricService.SHARED_NOTIF_ID, LyricService.buildSharedNotification(this))
        when (intent?.action) {
            ACTION_START_KEEPER -> {
                val pkg = intent.getStringExtra(EXTRA_PKG)
                val tid = intent.getIntExtra(EXTRA_TASK_ID, -1)
                if (pkg != null && tid > 0) {
                    keeperApp = RearTools.AppTask(pkg, tid)
                    registerProximity()
                    Log.i(TAG, "keeper start $pkg:$tid")
                }
            }
            ACTION_STOP_KEEPER -> endKeeper()
            ACTION_SYNC -> {
                // 刚开启充电动画且当前正在充电 → 立即投一次（无需等下次插电）。
                if (ToolsConfigState.current.chargeAnimation && ChargeOverlay.isCharging(this)) {
                    ChargeOverlay.project(applicationContext)
                }
            }
        }
        stopIfIdle()
        return START_STICKY
    }

    /** 不再有任何活儿（无投放保活、未投放常亮、未开充电动画）就收尾。 */
    private fun stopIfIdle() {
        val cfg = ToolsConfigState.current
        if (keeperApp == null && !cfg.alwaysWakeUp && !cfg.chargeAnimation) {
            Log.i(TAG, "idle -> stop")
            stopSelf()
        }
    }

    private fun startWorker() {
        if (worker != null) return
        worker = thread(name = "zhi-tools-keeper", isDaemon = true) {
            var sinceMonitor = 0L
            while (running) {
                val cfg = ToolsConfigState.current
                val app = keeperApp
                val needWake = cfg.alwaysWakeUp || (app != null && cfg.keepScreenOn)
                if (needWake) RearTools.wakeRear()

                // 投放保活：每 ~2s 检查应用是否还在背屏，离开则收尾。
                if (app != null) {
                    sinceMonitor += WAKE_INTERVAL_MS
                    if (sinceMonitor >= MONITOR_INTERVAL_MS) {
                        sinceMonitor = 0L
                        if (!RearTools.isTaskOnDisplay(app.taskId, RearTools.REAR_DISPLAY_ID)) {
                            Log.i(TAG, "kept app left rear -> end keeper")
                            main.post { endKeeper() }
                        }
                    }
                }
                try {
                    Thread.sleep(WAKE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun endKeeper() {
        keeperApp = null
        unregisterProximity()
        stopIfIdle()
        // 仍需未投放常亮则继续运行（worker 会据 keeperApp=null 切到只唤醒）。
    }

    // ───────────────────────── 遮盖检测（背屏接近传感器） ─────────────────────────

    private fun registerProximity() {
        if (proximity != null) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        sensorManager = sm
        proximity = sensor
        covered = false
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterProximity() {
        sensorManager?.unregisterListener(this)
        proximity = null
        covered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = proximity ?: return
        if (event.sensor != sensor) return
        if (!ToolsConfigState.current.coverDetection) return
        val isCovered = event.values[0] < sensor.maximumRange * 0.2f
        val now = System.currentTimeMillis()
        if (isCovered && !covered) {
            covered = true
            coveredSince = now
            main.postDelayed({
                if (covered && System.currentTimeMillis() - coveredSince >= COVER_DEBOUNCE_MS) {
                    onCovered()
                }
            }, COVER_DEBOUNCE_MS)
        } else if (!isCovered && covered) {
            covered = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 背屏被覆盖确认 → 把投放的应用拉回主屏并收尾。 */
    private fun onCovered() {
        if (keeperApp == null) return
        thread(name = "zhi-cover-return", isDaemon = true) {
            val app = RearTools.returnRearToMain()
            if (app != null) {
                main.post {
                    Toast.makeText(this, "背屏被遮盖，已返回主屏", Toast.LENGTH_SHORT).show()
                }
            }
            main.post { endKeeper() }
        }
    }

    // ───────────────────────── 杂项 ─────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhi:tools").apply {
            setReferenceCounted(false)
            runCatching { acquire(12 * 60 * 60 * 1000L) }
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        unregisterProximity()
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        RUNNING = false
        // 退出前台：还有别的保活服务在跑就 DETACH 保留那条共享通知，否则 REMOVE。
        ForegroundCoordinator.stopped(ForegroundCoordinator.TAG_TOOLS)
        if (ForegroundCoordinator.othersRunning(ForegroundCoordinator.TAG_TOOLS)) {
            @Suppress("DEPRECATION") stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ZhiToolsService"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_INTERVAL_MS = 250L
        private const val MONITOR_INTERVAL_MS = 2000L
        private const val COVER_DEBOUNCE_MS = 1500L

        const val ACTION_START_KEEPER = "com.zhitool.rearlyric.tools.START_KEEPER"
        const val ACTION_STOP_KEEPER = "com.zhitool.rearlyric.tools.STOP_KEEPER"
        const val ACTION_SYNC = "com.zhitool.rearlyric.tools.SYNC"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TASK_ID = "taskId"

        @Volatile
        private var RUNNING = false

        private fun start(ctx: Context, action: String, fill: (Intent) -> Unit = {}) {
            val i = Intent(ctx, RearToolsService::class.java).setAction(action).also(fill)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** 投放应用到背屏后调用：开始保活 + 遮盖检测。 */
        fun startKeeper(ctx: Context, app: RearTools.AppTask) {
            start(ctx, ACTION_START_KEEPER) {
                it.putExtra(EXTRA_PKG, app.packageName)
                it.putExtra(EXTRA_TASK_ID, app.taskId)
            }
        }

        /** 应用已收回主屏：结束保活。 */
        fun stopKeeper(ctx: Context) {
            if (RUNNING) start(ctx, ACTION_STOP_KEEPER)
        }

        /** 配置变化后调用：据 alwaysWakeUp / chargeAnimation 启动或复核服务（无活儿会自行收尾）。 */
        fun syncFromConfig(ctx: Context) {
            val cfg = ToolsConfigState.current
            if (cfg.alwaysWakeUp || cfg.chargeAnimation || RUNNING) start(ctx, ACTION_SYNC)
        }
    }
}
