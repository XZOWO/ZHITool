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
 * 采用 MRSS 已验证的流程——主屏拉起 → 轮询 taskId → `service call activity_task 50`
 * 把任务移动到副屏。副屏 displayId 固定为 1。
 */
object RearProjector {
    private const val TAG = "ZhiRearProjector"
    private const val REAR_DISPLAY_ID = 1
    private const val PKG = "com.zhitool.rearlyric"
    private const val ACTIVITY = "$PKG/.rear.RearLyricActivity"

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

        // 2. 主屏拉起歌词 Activity
        RootShell.run("am start -n $ACTIVITY")

        // 3. 轮询拿到它的 taskId：优先用 Activity 自报的（同进程，可靠），
        //    解析 am stack list 仅作兜底。解析失败导致不移屏时，Activity
        //    侧还有 ensureOnRearDisplay 自愈，双保险。
        var taskId: Int? = null
        for (i in 0 until 30) {
            taskId = LyricBus.rearTaskId.value.takeIf { it > 0 } ?: findTaskId()
            if (taskId != null) break
            Thread.sleep(40)
        }
        val tid = taskId ?: run {
            Log.w(TAG, "rear activity taskId not found")
            return false
        }

        // 4. 移动任务到副屏 display 1
        val moved = RootShell.run(
            "service call activity_task 50 i32 $tid i32 $REAR_DISPLAY_ID"
        )
        Log.i(TAG, "projected taskId=$tid moved=$moved")
        return moved
    }

    /** 收回：通知背屏 Activity 自行关闭。 */
    fun hide() {
        LyricBus.projected.value = false
    }

    private fun findTaskId(): Int? {
        val out = RootShell.exec("am stack list").output
        for (line in out.lineSequence()) {
            if (line.contains("RearLyricActivity") && line.contains("taskId=")) {
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
