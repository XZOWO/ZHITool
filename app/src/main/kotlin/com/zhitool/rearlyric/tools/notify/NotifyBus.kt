/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** 一条通知。[id] 保证每次都是新值，便于触发动画与计时。 */
data class NotifyPayload(
    val pkg: String,
    val title: String,
    val text: String,
    val autoSec: Int,
    /** SystemUI notification identity and opaque click capability. */
    val notificationKey: String? = null,
    val clickToken: String? = null,
    val openable: Boolean = false,
    val id: Long = System.nanoTime(),
)

/**
 * 监听器与背屏通知 UI 之间的进程内通道（同进程，免去 shell 转义/跨进程）。
 *
 * - [items]：当前一批通知（卡片页堆叠显示，滚动上限 [MAX]）。
 * - [pill]：在歌词/充电页**上方**浮出的安卓原生式胶囊（轻量，不重绘当前页）。
 * - [cardVisible]：卡片页是否正显示在背屏（决定新通知走"加入堆叠"还是走"胶囊/整页"）。
 */
object NotifyBus {
    private const val MAX = 8

    private val _items = MutableStateFlow<List<NotifyPayload>>(emptyList())
    val items: StateFlow<List<NotifyPayload>> = _items.asStateFlow()

    private val _pill = MutableStateFlow<NotifyPayload?>(null)
    val pill: StateFlow<NotifyPayload?> = _pill.asStateFlow()

    @Volatile
    var cardVisible = false

    // 当前**正在前台显示**、能承载通知胶囊的页（歌词/充电）。用 onResume/onPause 维护，
    // 比 RearStage.occupied 可靠——避免某页已退出但 token 残留时把通知误判成胶囊却无人渲染。
    private val pillHosts = ConcurrentHashMap.newKeySet<Any>()
    val pillHostActive: Boolean get() = pillHosts.isNotEmpty()
    fun pillHostResumed(token: Any) { pillHosts.add(token) }
    fun pillHostPaused(token: Any) { pillHosts.remove(token) }

    fun add(p: NotifyPayload) {
        _items.value = (_items.value + p).takeLast(MAX)
    }

    fun showPill(p: NotifyPayload) {
        _pill.value = p
    }

    fun clearPill() {
        _pill.value = null
    }

    /** 卡片页关闭时清空本批通知。 */
    fun clearItems() {
        _items.value = emptyList()
    }
}
