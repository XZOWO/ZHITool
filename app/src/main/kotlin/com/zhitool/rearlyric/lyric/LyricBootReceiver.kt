/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.lyric

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机完成后恢复歌词前台服务；Root 探测在服务自己的后台队列中执行。 */
class LyricBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "boot completed -> start lyric service")
        runCatching { LyricService.start(context.applicationContext) }
            .onFailure { Log.e(TAG, "start lyric service after boot failed", it) }
    }

    companion object {
        private const val TAG = "ZhiLyricBoot"
    }
}
