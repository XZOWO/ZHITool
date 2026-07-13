/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

/** Primitive-only notification protocol between the LSPosed SystemUI process and ZHITool. */
object NotificationBridge {
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val ACTION_POSTED = "com.zhitool.rearlyric.action.NOTIFICATION_POSTED"
    const val ACTION_CLICK = "com.zhitool.rearlyric.action.NOTIFICATION_CLICK"

    const val EXTRA_KEY = "notification_key"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_OPENABLE = "openable"
    const val EXTRA_CLICK_TOKEN = "click_token"
}
