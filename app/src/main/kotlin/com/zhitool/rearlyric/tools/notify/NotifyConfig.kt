/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背屏通知推送配置。
 * - [enabled] 总开关。[selectedApps] 需推送的应用包名。
 * - [hideTitle]/[hideContent] 隐私模式。[followDnd] 跟随系统勿扰。
 * - [onlyWhenUpsideDown] 仅倒扣手机时（主屏接近传感器被遮）。
 * - [autoDestroySeconds] 自动消失秒数。
 */
data class NotifyConfig(
    val enabled: Boolean = false,
    val selectedApps: Set<String> = emptySet(),
    val hideTitle: Boolean = false,
    val hideContent: Boolean = false,
    val followDnd: Boolean = true,
    val onlyWhenUpsideDown: Boolean = false,
    val autoDestroySeconds: Int = 5,
)

object NotifyConfigState {
    private val _flow = MutableStateFlow(NotifyConfig())
    val flow: StateFlow<NotifyConfig> = _flow.asStateFlow()
    val current: NotifyConfig get() = _flow.value

    internal fun set(cfg: NotifyConfig) {
        _flow.value = cfg
    }
}

object NotifyConfigStore {
    private const val PREFS = "zhi_notify"
    private const val K_ENABLED = "enabled"
    private const val K_APPS = "selected_apps"
    private const val K_HIDE_TITLE = "hide_title"
    private const val K_HIDE_CONTENT = "hide_content"
    private const val K_FOLLOW_DND = "follow_dnd"
    private const val K_UPSIDE_DOWN = "only_upside_down"
    private const val K_AUTO_SEC = "auto_destroy_sec"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): NotifyConfig {
        val p = prefs(ctx)
        val cfg = NotifyConfig(
            enabled = p.getBoolean(K_ENABLED, false),
            selectedApps = p.getStringSet(K_APPS, emptySet())?.toSet() ?: emptySet(),
            hideTitle = p.getBoolean(K_HIDE_TITLE, false),
            hideContent = p.getBoolean(K_HIDE_CONTENT, false),
            followDnd = p.getBoolean(K_FOLLOW_DND, true),
            onlyWhenUpsideDown = p.getBoolean(K_UPSIDE_DOWN, false),
            autoDestroySeconds = p.getInt(K_AUTO_SEC, 5).coerceIn(1, 3600),
        )
        NotifyConfigState.set(cfg)
        return cfg
    }

    fun save(ctx: Context, cfg: NotifyConfig) {
        prefs(ctx).edit()
            .putBoolean(K_ENABLED, cfg.enabled)
            .putStringSet(K_APPS, cfg.selectedApps)
            .putBoolean(K_HIDE_TITLE, cfg.hideTitle)
            .putBoolean(K_HIDE_CONTENT, cfg.hideContent)
            .putBoolean(K_FOLLOW_DND, cfg.followDnd)
            .putBoolean(K_UPSIDE_DOWN, cfg.onlyWhenUpsideDown)
            .putInt(K_AUTO_SEC, cfg.autoDestroySeconds)
            .apply()
        NotifyConfigState.set(cfg)
    }
}
