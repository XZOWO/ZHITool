/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 背屏投影流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0)
 * 与 REAREye (https://github.com/killerprojecte/REAREye, GPL-3.0)。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.rear

import android.util.Log
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.lyric.LyricBus

/**
 * 背屏投放器：用 root 把 [RearLyricActivity] 投放到副屏(display 1)。
 *
 * 优化（参考 MRSS 通知路径）：**优先直接 `am start --display 1`** 拉到副屏，轮询确认已在副屏即结束——
 * 这样根本不在正面闪现、不吞正面触控；只有直拉没落到副屏时才回退「移屏」。配合 RearLyricActivity
 * 的「未到副屏前透明不可触」占位，即使短暂落到正面也不可见、不吞触控。
 */
object RearProjector {
    private const val TAG = "ZhiRearProjector"
    private const val REAR_DISPLAY_ID = 1
    private const val PKG = "com.zhitool.rearlyric"
    private const val ACTIVITY = "$PKG/.rear.RearLyricActivity"
    private const val ACTIVITY_NAME = "RearLyricActivity"

    /** 投放歌词页到副屏。返回是否成功。 */
    fun show(): Boolean {
        if (!RootShell.available) {
            Log.w(TAG, "root not available")
            return false
        }
        LyricBus.projected.value = true

        // 1. 唤醒副屏，避免黑屏
        RootShell.run("input -d $REAR_DISPLAY_ID keyevent KEYCODE_WAKEUP")
        Thread.sleep(50)

        // 2. 优先直接在副屏拉起
        RootShell.run("am start --display $REAR_DISPLAY_ID -n $ACTIVITY")

        // 3. 轮询确认已在副屏（最多 ~600ms）
        for (i in 0 until 15) {
            if (isOnRear()) return true
            Thread.sleep(40)
        }

        // 4. 没落到副屏 → 拿 taskId（Activity 自报优先，解析兜底）后移屏，校验+重试
        var tid = LyricBus.rearTaskId.value.takeIf { it > 0 }
        for (i in 0 until 20) {
            if (tid != null && tid > 0) break
            tid = findTaskId()
            if (tid != null) break
            Thread.sleep(40)
        }
        val taskId = tid ?: run {
            Log.w(TAG, "rear lyric taskId not found")
            return false
        }
        for (i in 0 until 4) {
            if (isOnRear()) return true
            RootShell.run("service call activity_task 50 i32 $taskId i32 $REAR_DISPLAY_ID")
            Thread.sleep(120)
        }
        Log.i(TAG, "projected lyric taskId=$taskId (moved)")
        return true
    }

    /** 收回：通知背屏 Activity 自行关闭。 */
    fun hide() {
        LyricBus.projected.value = false
    }

    /** 歌词页是否已在副屏的某个 RootTask 中。 */
    private fun isOnRear(): Boolean {
        val out = RootShell.exec("am stack list").output
        var inRear = false
        for (line in out.lineSequence()) {
            if (line.startsWith("RootTask")) {
                inRear = line.contains("displayId=$REAR_DISPLAY_ID")
                continue
            }
            if (inRear && line.contains(ACTIVITY_NAME)) return true
        }
        return false
    }

    private fun findTaskId(): Int? {
        val out = RootShell.exec("am stack list").output
        for (line in out.lineSequence()) {
            if (line.contains(ACTIVITY_NAME) && line.contains("taskId=")) {
                val start = line.indexOf("taskId=") + 7
                val end = line.indexOf(':', start)
                if (end > start) {
                    return line.substring(start, end).trim().toIntOrNull()
                }
            }
        }
        return null
    }
}
