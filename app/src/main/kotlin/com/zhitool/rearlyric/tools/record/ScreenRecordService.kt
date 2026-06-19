/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 录屏流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 ScreenRecordService；
 * 悬浮窗重绘为 ZHITool 新增。背屏只能用 root screenrecord 抓，纯视频无音频。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zhitool.rearlyric.R
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.ui.MainActivity
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背屏录屏服务：悬浮窗控制 + root `screenrecord` 抓背屏视频（纯视频，无音频）。
 *
 * 悬浮窗：黑色半透明胶囊，左=红色录屏按钮（圆↔圆角方），右=打开文件管理到 Movies 目录。
 */
class ScreenRecordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var recordButton: View? = null

    @Volatile private var recording = false
    private var recordPid = -1
    private var moviesDest: String? = null
    @Volatile private var wakeRunning = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        _shown.value = true
        goForeground()
        showFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ───────────────────────── 悬浮窗 ─────────────────────────

    private fun showFloatingWindow() {
        if (floatingView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm
        val view = buildFloatingView()
        floatingView = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(160)
        }
        runCatching { wm.addView(view, params) }
            .onFailure { Log.e(TAG, "addView failed", it); stopSelf() }
    }

    private fun buildFloatingView(): View {
        // 整体比上一版缩小约 30%。
        val capsuleH = dp(45)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = capsuleH / 2f
                setColor(0xB3000000.toInt()) // 黑色 70% 半透明
            }
        }

        // 左：红色录屏按钮
        val btn = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(31), dp(31))
            setOnClickListener { onRecordButtonClick() }
        }
        recordButton = btn
        updateRecordButton(false)

        // 中：打开文件管理到录屏目录
        val folder = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(10) }
            setImageResource(R.drawable.ic_record_folder)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { openRecordingsFolder() }
        }

        // 右：关闭悬浮窗
        val close = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(10) }
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { stopEverything() }
        }

        layout.addView(btn)
        layout.addView(folder)
        layout.addView(close)
        attachDrag(layout)
        return layout
    }

    private fun updateRecordButton(rec: Boolean) {
        val btn = recordButton ?: return
        btn.background = GradientDrawable().apply {
            if (rec) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
            } else {
                shape = GradientDrawable.OVAL
            }
            setColor(0xFFE8453C.toInt()) // 红
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun attachDrag(layout: View) {
        layout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x; initialY = lp.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = initialX + (touchX - event.rawX).toInt() // 靠右，反向
                        lp.y = initialY + (event.rawY - touchY).toInt()
                        runCatching { windowManager?.updateViewLayout(floatingView, lp) }
                        return true
                    }
                }
                return false
            }
        })
    }

    /** 打开文件管理器（优先 MT 管理器）定位到 Movies 目录；不弹分享面板。 */
    private fun openRecordingsFolder() {
        val dirUri = Uri.parse("file:///storage/emulated/0/Movies/")
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Movies",
        )
        val candidates = listOfNotNull(
            // MT 管理器定位到目录
            Intent(Intent.ACTION_VIEW).setDataAndType(dirUri, "resource/folder").setPackage(MT_PKG),
            Intent(Intent.ACTION_VIEW).setDataAndType(dirUri, "*/*").setPackage(MT_PKG),
            // 系统文件 / DocumentsUI 打开目录
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR),
            // 各家文件管理器主页兜底
            packageManager.getLaunchIntentForPackage(MT_PKG),
            packageManager.getLaunchIntentForPackage("com.android.fileexplorer"),
            packageManager.getLaunchIntentForPackage("com.mi.android.globalFileexplorer"),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null &&
                runCatching { startActivity(intent) }.isSuccess
            ) return
        }
        toast("未找到文件管理器（录屏在 Movies 目录）")
    }

    // ───────────────────────── 录制 ─────────────────────────

    private fun onRecordButtonClick() {
        if (recording) stopRecording() else beginRecording()
    }

    private fun beginRecording() {
        if (recording) return
        recording = true
        main.post { updateRecordButton(true) }
        thread(name = "zhi-rec-start") { doStartRecording() }
    }

    private fun doStartRecording() {
        if (!RootShell.available && !RootShell.refresh()) {
            recording = false
            main.post { updateRecordButton(false) }
            toast("请先授权 Root")
            return
        }
        RootShell.run("input -d 1 keyevent KEYCODE_WAKEUP")
        Thread.sleep(200)

        val displayId = rearDisplayArg()
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val dest = "/storage/emulated/0/Movies/ZHITool_$ts.mp4"
        moviesDest = dest
        RootShell.run("mkdir -p /storage/emulated/0/Movies")

        val pidFile = "/data/local/tmp/zhi_record.pid"
        val cmd = "screenrecord --display-id $displayId --bit-rate $REC_BITRATE " +
            "\"$dest\" > /data/local/tmp/zhi_record.log 2>&1 & echo \$! > $pidFile"
        RootShell.run(cmd)
        Thread.sleep(800)
        recordPid = RootShell.exec("cat $pidFile").output.trim().toIntOrNull() ?: -1
        Log.i(TAG, "screenrecord pid=$recordPid target=$dest")

        startRearWakeup()
        toast("开始录制背屏")
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        wakeRunning = false
        main.post { updateRecordButton(false) }
        thread(name = "zhi-rec-stop") { doStopRecording() }
    }

    private fun doStopRecording() {
        if (recordPid > 0) {
            if (!RootShell.run("kill -2 $recordPid")) RootShell.run("kill $recordPid")
        }
        Thread.sleep(1200)
        recordPid = -1
        moviesDest?.let {
            RootShell.run("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$it")
            toast("录屏已保存到 Movies")
        }
    }

    private fun startRearWakeup() {
        if (wakeRunning) return
        wakeRunning = true
        thread(name = "zhi-rec-wake", isDaemon = true) {
            while (wakeRunning && recording) {
                RootShell.run("input -d 1 keyevent KEYCODE_WAKEUP")
                try { Thread.sleep(120) } catch (_: InterruptedException) { break }
            }
        }
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private fun rearDisplayArg(): String {
        val id = RootShell.exec(
            "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print \$2}'",
        ).output.trim()
        return id.ifBlank { "1" }
    }

    private fun goForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        com.zhitool.rearlyric.core.ServiceNotice.ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, com.zhitool.rearlyric.core.ServiceNotice.CHANNEL)
            .setContentTitle("背屏录屏")
            .setContentText("录屏悬浮窗已开启")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setGroup(com.zhitool.rearlyric.core.ServiceNotice.GROUP)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun stopEverything() {
        if (recording) {
            recording = false
            wakeRunning = false
            thread(name = "zhi-rec-finalize") { doStopRecording() }
        }
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        val v = floatingView ?: return
        runCatching { windowManager?.removeView(v) }
        floatingView = null
    }

    override fun onDestroy() {
        wakeRunning = false
        recording = false
        removeOverlay()
        instance = null
        _shown.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ZhiRecordService"
        private const val NOTIFICATION_ID = 4712
        private const val REC_BITRATE = 20_000_000 // 固定 20 Mbps
        private const val MT_PKG = "bin.mt.plus"
        const val ACTION_SHOW = "com.zhitool.rearlyric.tools.record.SHOW"
        const val ACTION_STOP = "com.zhitool.rearlyric.tools.record.STOP"

        @Volatile
        private var instance: ScreenRecordService? = null

        /** 悬浮窗是否显示中（供工具页开关观察）。 */
        private val _shown = MutableStateFlow(false)
        val shownFlow: StateFlow<Boolean> = _shown.asStateFlow()

        val isRunning: Boolean get() = instance != null
    }
}
