/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.rear

import java.util.concurrent.ConcurrentHashMap

/**
 * 背屏占用登记：歌词 / 充电 / 通知等投到副屏的 Activity 在显示期间登记自己。
 *
 * 投影器据 [occupied] 决定——背屏已被我们的页占着时，新页**直接 `am start --display 1`**
 * 拉到副屏，而不是再走「主屏拉起 + 移屏」（重复移屏偶尔会把页留在正面屏幕）。
 */
object RearStage {
    private val tokens = ConcurrentHashMap.newKeySet<Any>()

    /** 当前是否有我们的页在背屏上。 */
    val occupied: Boolean get() = tokens.isNotEmpty()

    fun enter(token: Any) {
        tokens.add(token)
    }

    fun leave(token: Any) {
        tokens.remove(token)
    }
}
