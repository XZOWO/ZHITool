/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 协调多个「保活前台服务」(歌词 / 工具) 共用**同一个**前台通知(同 ID)。
 *
 * 谁活着就登记 tag；某个服务结束前台时据 [othersRunning] 决定：还有别的保活服务在跑就
 * `STOP_FOREGROUND_DETACH`(保留那条共享通知给对方),否则 `STOP_FOREGROUND_REMOVE`。
 * 这样无论一个还是多个保活服务在跑,通知栏始终只有一条保活通知。
 */
object ForegroundCoordinator {
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun started(tag: String) { active.add(tag) }
    fun stopped(tag: String) { active.remove(tag) }

    /** 除自己外是否还有其它保活前台服务在跑。 */
    fun othersRunning(selfTag: String): Boolean = active.any { it != selfTag }

    const val TAG_LYRIC = "lyric"
    const val TAG_TOOLS = "tools"
}
