/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 背屏投影流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0)。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.overlay

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import com.zhitool.rearlyric.core.RootShell

/**
 * 充电 / 通知等背屏临时 Activity 的共享投影与窗口工具。
 *
 * **投影策略（参考 MRSS 通知路径的优化）**：优先直接 `am start --display 1` 把页拉到副屏，
 * 轮询确认已在副屏即结束——这样根本不在正面出现、不吞正面触控；只有当直拉没落到副屏时
 * 才回退「找 taskId → `service call activity_task 50` 移屏」。配合 Activity 的「未到副屏前
 * 透明且不可触」占位（见各 Activity 的 onRear 门控），即使短暂落到正面也不可见、不吞触控。
 */
object RearOverlaySupport {
    const val REAR_DISPLAY_ID = 1

    /** 锁屏可显示 + 点亮 + 常亮（旧 flags 在新系统可能被忽略，配合新 API）。 */
    fun applyLockScreenFlags(a: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            a.setShowWhenLocked(true)
            a.setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        a.window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
    }

    /**
     * 「未到副屏前」的占位窗口标记：不可触（触控穿透到正面下方的应用）+ 不获焦。
     * 一旦到了副屏就清除，让副屏内容可正常交互（通知滑动等）。
     */
    fun applyPlaceholderFlags(a: Activity, onRear: Boolean) {
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (onRear) a.window.clearFlags(flags) else a.window.addFlags(flags)
    }

    fun currentDisplayId(a: Activity): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { a.display?.displayId }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            a.windowManager.defaultDisplay?.displayId
        }

    /**
     * 把页投到副屏。**必须在后台线程调用**（走 RootShell 阻塞命令）。
     * @param amStartCmd 形如 `am start -n <pkg>/<activity> [--extras]`（内部会插入 `--display 1`）。
     * @param taskIdHint 可选，Activity 自报的 taskId（更可靠，省去解析）。
     */
    fun project(amStartCmd: String, activitySimpleName: String, taskIdHint: () -> Int? = { null }) {
        if (!RootShell.available && !RootShell.refresh()) return
        RootShell.run("input -d $REAR_DISPLAY_ID keyevent KEYCODE_WAKEUP")

        // 1) 优先直接拉到副屏。
        val direct = if (amStartCmd.contains("--display ")) amStartCmd
        else amStartCmd.replaceFirst("am start ", "am start --display $REAR_DISPLAY_ID ")
        RootShell.run(direct)

        // 2) 轮询确认已在副屏（最多 ~600ms）。
        for (i in 0 until 15) {
            if (isActivityOnDisplay(activitySimpleName, REAR_DISPLAY_ID)) return
            Thread.sleep(40)
        }

        // 3) 没落到副屏（可能被系统落到正面）→ 找 taskId 移过去，并校验+重试。
        var tid = taskIdHint()
        for (i in 0 until 20) {
            if (tid != null && tid > 0) break
            tid = findTaskId(activitySimpleName)
            if (tid != null) break
            Thread.sleep(40)
        }
        val taskId = tid ?: return
        for (i in 0 until 4) {
            if (isActivityOnDisplay(activitySimpleName, REAR_DISPLAY_ID)) return
            RootShell.run("service call activity_task 50 i32 $taskId i32 $REAR_DISPLAY_ID")
            Thread.sleep(120)
        }
    }

    /** 自愈：误留主屏时用自己的 taskId 移到副屏。后台线程调用。 */
    fun selfHealToRear(taskId: Int) {
        RootShell.run("service call activity_task 50 i32 $taskId i32 $REAR_DISPLAY_ID")
    }

    /** 指定 Activity（按短名匹配）是否在指定 display 的某个 RootTask 中。 */
    fun isActivityOnDisplay(activitySimpleName: String, displayId: Int): Boolean {
        val out = RootShell.exec("am stack list").output
        var inTarget = false
        for (line in out.lineSequence()) {
            if (line.startsWith("RootTask")) {
                inTarget = line.contains("displayId=$displayId")
                continue
            }
            if (inTarget && line.contains(activitySimpleName)) return true
        }
        return false
    }

    private fun findTaskId(name: String): Int? {
        val out = RootShell.exec("am stack list").output
        for (line in out.lineSequence()) {
            if (line.contains(name) && line.contains("taskId=")) {
                val start = line.indexOf("taskId=") + 7
                val end = line.indexOf(':', start)
                if (end > start) return line.substring(start, end).trim().toIntOrNull()
            }
        }
        return null
    }
}
