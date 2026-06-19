/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 背屏工具命令流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 TaskService。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

import android.util.Log

/**
 * 背屏系统工具的命令层（移植自 MRSS 的 TaskService）。
 *
 * MRSS 把命令放在 LSPosed 注入的高权限进程里、用 AIDL 绑定调用；ZHITool 统一改成
 * [RootShell] 直接以 root 执行——同一套 `am stack list` / `service call activity_task 50`
 * / `wm density` / `wm user-rotation` / `screencap` 命令，但无需绑定服务。
 *
 * **所有方法均为阻塞式（走长驻 su 会话），必须在后台线程调用。** 不持有 Context、不碰 UI。
 */
object RearTools {
    private const val TAG = "ZhiRearTools"

    const val REAR_DISPLAY_ID = 1
    const val MAIN_DISPLAY_ID = 0
    private const val SELF_PKG = "com.zhitool.rearlyric"
    private const val SUBSCREEN_PKG = "com.xiaomi.subscreencenter"

    /** 一个任务：包名 + taskId。 */
    data class AppTask(val packageName: String, val taskId: Int)

    // ───────────────────────── 前台应用查询 ─────────────────────────

    /** 主屏(display 0)当前前台应用，跳过 Launcher 与自身。无则 null。 */
    fun getCurrentForegroundApp(): AppTask? = parseForeground(MAIN_DISPLAY_ID, skipLauncherAndSelf = true)

    /** 指定 display 的前台应用（不跳过任何包）。无则 null。 */
    fun getForegroundAppOnDisplay(displayId: Int): AppTask? =
        parseForeground(displayId, skipLauncherAndSelf = false)

    private fun parseForeground(displayId: Int, skipLauncherAndSelf: Boolean): AppTask? {
        val out = RootShell.exec("am stack list").output
        var inTarget = false
        for (line in out.lineSequence()) {
            if (line.startsWith("RootTask")) {
                inTarget = line.contains("displayId=$displayId")
                continue
            }
            if (inTarget && line.contains("taskId=") && line.contains("/")) {
                val tidStart = line.indexOf("taskId=") + 7
                val tidEnd = line.indexOf(':', tidStart)
                if (tidEnd <= tidStart) continue
                val taskId = line.substring(tidStart, tidEnd).trim().toIntOrNull() ?: continue
                val pkgStart = tidEnd + 2
                val pkgEnd = line.indexOf('/', pkgStart)
                if (pkgEnd <= pkgStart) continue
                val pkg = line.substring(pkgStart, pkgEnd).trim()
                if (skipLauncherAndSelf &&
                    (pkg.contains("launcher") || pkg.contains("miui.home") || pkg == SELF_PKG)
                ) continue
                return AppTask(pkg, taskId)
            }
        }
        return null
    }

    /** 任务是否在指定 display 上。 */
    fun isTaskOnDisplay(taskId: Int, displayId: Int): Boolean {
        val out = RootShell.exec("am stack list").output
        var inTarget = false
        for (line in out.lineSequence()) {
            if (line.startsWith("RootTask")) {
                inTarget = line.contains("displayId=$displayId")
                continue
            }
            if (inTarget && line.contains("taskId=$taskId")) return true
        }
        return false
    }

    // ───────────────────────── 移屏 / Launcher ─────────────────────────

    /** `service call activity_task 50` 把任务移动到指定 display。 */
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean =
        RootShell.run("service call activity_task 50 i32 $taskId i32 $displayId")

    /** 禁用系统背屏 Launcher（防止挤占投放的应用）。 */
    fun disableSubScreenLauncher(): Boolean = RootShell.run("am force-stop $SUBSCREEN_PKG")

    /** 恢复系统背屏 Launcher。 */
    fun enableSubScreenLauncher(): Boolean =
        RootShell.run("am start --display $REAR_DISPLAY_ID -n $SUBSCREEN_PKG/.SubScreenLauncher")

    /** 收起控制中心 / 状态栏。 */
    fun collapseStatusBar(): Boolean = RootShell.run("cmd statusbar collapse")

    /** 给背屏发一次 keycode 唤醒，避免黑屏。 */
    fun wakeRear() {
        RootShell.run("input -d $REAR_DISPLAY_ID keyevent KEYCODE_WAKEUP")
    }

    // ───────────────────────── 切换 / 返回 ─────────────────────────

    /**
     * 把主屏当前前台应用切换到背屏。返回被移动的任务（含包名/taskId），失败返回 null。
     * 流程：禁背屏 Launcher → 取前台应用 → 移屏 → 唤醒背屏。
     */
    fun switchCurrentAppToRear(): AppTask? {
        if (!RootShell.available) return null
        disableSubScreenLauncher()
        val app = getCurrentForegroundApp() ?: run {
            Log.w(TAG, "no foreground app on main display")
            return null
        }
        val moved = moveTaskToDisplay(app.taskId, REAR_DISPLAY_ID)
        if (!moved) {
            Log.w(TAG, "moveTaskToDisplay failed for $app")
            return null
        }
        wakeRear()
        Log.i(TAG, "switched $app to rear")
        return app
    }

    /**
     * 把背屏当前前台应用拉回主屏并恢复系统 Launcher。返回被拉回的任务，失败返回 null。
     */
    fun returnRearToMain(): AppTask? {
        if (!RootShell.available) return null
        val app = getForegroundAppOnDisplay(REAR_DISPLAY_ID) ?: run {
            Log.w(TAG, "no app on rear display")
            return null
        }
        val moved = moveTaskToDisplay(app.taskId, MAIN_DISPLAY_ID)
        enableSubScreenLauncher()
        Log.i(TAG, "returned $app to main, moved=$moved")
        return if (moved) app else null
    }

    // ───────────────────────── 截图 ─────────────────────────

    /** 截取背屏画面保存到相册并刷新媒体库。 */
    fun takeRearScreenshot(): Boolean {
        if (!RootShell.available) return false
        wakeRear()
        Thread.sleep(200)
        RootShell.run("mkdir -p /storage/emulated/0/Pictures/RearDisplay")

        // 优先解析 SurfaceFlinger 第二块物理屏的 display id，失败回退 1。
        val sfId = RootShell.exec(
            "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print \$2}'"
        ).output.trim()
        val displayArg = sfId.ifBlank { REAR_DISPLAY_ID.toString() }

        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = "/storage/emulated/0/Pictures/RearDisplay/RD_$ts.png"
        val ok = RootShell.run("screencap -p -d $displayArg $file")
        RootShell.run("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$file")
        Log.i(TAG, "screenshot $file ok=$ok")
        return ok
    }

    // ───────────────────────── DPI ─────────────────────────

    /** 读背屏当前 DPI（优先 Override，否则 Physical）。0 表示失败。 */
    fun getRearDpi(): Int {
        val out = RootShell.exec("wm density -d $REAR_DISPLAY_ID").output
        var physical = 0
        for (line in out.lineSequence()) {
            if (!line.contains("density:")) continue
            val n = line.substringAfter(':').trim().toIntOrNull() ?: continue
            if (line.contains("Override density")) return n
            if (physical == 0) physical = n
        }
        return physical
    }

    fun setRearDpi(dpi: Int): Boolean = RootShell.run("wm density $dpi -d $REAR_DISPLAY_ID")

    fun resetRearDpi(): Boolean = RootShell.run("wm density reset -d $REAR_DISPLAY_ID")

    // ───────────────────────── 旋转 ─────────────────────────

    /** 读背屏旋转方向(0=0°,1=90°,2=180°,3=270°)，解析失败返回 0。 */
    fun getRearRotation(): Int {
        val line = RootShell.exec("wm user-rotation -d $REAR_DISPLAY_ID").output.trim()
        // 输出形如 "lock 2" 或 "free"
        val parts = line.split(Regex("\\s+"))
        return if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
    }

    /**
     * 锁定背屏旋转方向；若背屏当前有应用，旋转后等 500ms 检查是否被杀，被杀则复活。
     */
    fun setRearRotation(rotation: Int): Boolean {
        val current = getForegroundAppOnDisplay(REAR_DISPLAY_ID)
        val ok = RootShell.run("wm user-rotation -d $REAR_DISPLAY_ID lock $rotation")
        if (ok && current != null) {
            Thread.sleep(500)
            if (!isTaskOnDisplay(current.taskId, REAR_DISPLAY_ID)) {
                moveTaskToDisplay(current.taskId, REAR_DISPLAY_ID)
            }
        }
        return ok
    }
}
