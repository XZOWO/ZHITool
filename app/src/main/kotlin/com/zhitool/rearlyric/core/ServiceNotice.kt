/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 统一的后台前台服务通知渠道与分组。
 *
 * 歌词 / 工具保活 / 录屏三个前台服务都用同一渠道（设置里只有一项）且同一 group——多个保活
 * 通知在通知栏会折叠成一组，避免「保活通知重复」。各服务仍用各自的通知 ID 与内容/按钮。
 */
object ServiceNotice {
    const val CHANNEL = "zhi_service"
    const val GROUP = "zhi_service_group"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ZHITool 后台服务", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        // 清理早期分散的渠道（升级用户的设置里不再残留多项）。
        listOf("zhi_lyric", "zhi_tools_service", "zhi_record_service").forEach {
            runCatching { nm.deleteNotificationChannel(it) }
        }
    }
}
