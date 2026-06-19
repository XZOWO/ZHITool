/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import android.content.Context
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlin.concurrent.thread

/** 背屏通知的两个动作：在背屏打开卡片页、在正面打开对应应用。 */
object NotifyActions {
    private const val CARD_ACTIVITY = "com.zhitool.rearlyric/.tools.notify.RearNotificationActivity"

    /** 在背屏拉起通知卡片页（占用时直接 --display 1，见 RearOverlaySupport）。 */
    fun openCardPage(context: Context) {
        NotifyBus.clearPill()
        thread(name = "zhi-notify-card") {
            RearOverlaySupport.project("am start -n $CARD_ACTIVITY", "RearNotificationActivity")
        }
    }

    /** 在正面（主屏）打开该通知对应的应用——等效于点击该通知。 */
    fun openOnFront(context: Context, pkg: String) {
        thread(name = "zhi-notify-front") {
            val comp = runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            }.getOrNull()
            if (comp != null) {
                // 不带 --display → 落到默认显示（正面）。
                RootShell.run("am start -n $comp")
            } else {
                RootShell.run(
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg",
                )
            }
        }
    }
}
