/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.rear

/** 歌词解锁与封面控制页共用的精确长按时长。 */
internal const val REAR_LONG_PRESS_MS = 1000L

/** 长按期间允许的最大手指位移；超过即取消，避免滑动误触。 */
internal const val REAR_LONG_PRESS_MOVE_CANCEL_DP = 16f

/** 封面控制页完全展开后，无触摸交互多久自动收起。 */
internal const val REAR_PANEL_IDLE_HIDE_MS = 5000L

/** 短按封面后的操作提示显示时长。 */
internal const val REAR_PANEL_HINT_MS = 2600L

internal const val REAR_PANEL_HINT_TEXT = "长按打开控制页"
