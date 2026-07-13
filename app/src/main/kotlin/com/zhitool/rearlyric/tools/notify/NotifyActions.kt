/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import android.content.Context
import android.content.Intent
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlin.concurrent.thread

/** 背屏通知动作：打开卡片页，或请求 SystemUI 执行原通知的点击动作。 */
object NotifyActions {
    private const val CARD_ACTIVITY = "com.zhitool.rearlyric/.tools.notify.RearNotificationActivity"

    /** 在背屏拉起通知卡片页（占用时直接 --display 1，见 RearOverlaySupport）。 */
    fun openCardPage(context: Context) {
        NotifyBus.clearPill()
        thread(name = "zhi-notify-card") {
            RearOverlaySupport.project("am start -n $CARD_ACTIVITY", "RearNotificationActivity")
        }
    }

    /** 原 PendingIntent 留在 SystemUI；这里只回传通知 key + 不可猜测令牌。 */
    fun openOnFront(context: Context, notification: NotifyPayload) {
        val key = notification.notificationKey ?: return
        val token = notification.clickToken ?: return
        if (!notification.openable) return
        context.applicationContext.sendBroadcast(
            Intent(NotificationBridge.ACTION_CLICK)
                .setPackage(NotificationBridge.SYSTEM_UI_PACKAGE)
                .putExtra(NotificationBridge.EXTRA_KEY, key)
                .putExtra(NotificationBridge.EXTRA_CLICK_TOKEN, token)
        )
    }
}
