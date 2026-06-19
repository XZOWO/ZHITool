/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背屏工具的开关配置（与歌词的 RearConfig 解耦）。
 *
 * - [keepScreenOn]   投放应用到背屏期间保持背屏常亮（keycode 持续唤醒）。
 * - [alwaysWakeUp]   未投放应用时也保持背屏常亮（耗电/烧屏警告）。
 * - [coverDetection] 背屏遮盖检测：背屏接近传感器被覆盖即把投放的应用拉回主屏。
 */
data class ToolsConfig(
    val keepScreenOn: Boolean = true,
    val alwaysWakeUp: Boolean = false,
    val coverDetection: Boolean = false,
    val chargeAnimation: Boolean = false,
    val chargeAlwaysOn: Boolean = false,
)

/** 进程内共享的工具配置（UI / 服务都观察这里）。 */
object ToolsConfigState {
    private val _flow = MutableStateFlow(ToolsConfig())
    val flow: StateFlow<ToolsConfig> = _flow.asStateFlow()
    val current: ToolsConfig get() = _flow.value

    internal fun set(cfg: ToolsConfig) {
        _flow.value = cfg
    }
}

/** ToolsConfig 的 SharedPreferences 持久化。 */
object ToolsConfigStore {
    private const val PREFS = "zhi_tools"
    private const val K_KEEP_ON = "keep_screen_on"
    private const val K_ALWAYS_WAKE = "always_wake_up"
    private const val K_COVER = "cover_detection"
    private const val K_CHARGE = "charge_animation"
    private const val K_CHARGE_ALWAYS = "charge_always_on"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 启动时载入到 [ToolsConfigState]。 */
    fun load(ctx: Context): ToolsConfig {
        val p = prefs(ctx)
        val cfg = ToolsConfig(
            keepScreenOn = p.getBoolean(K_KEEP_ON, true),
            alwaysWakeUp = p.getBoolean(K_ALWAYS_WAKE, false),
            coverDetection = p.getBoolean(K_COVER, false),
            chargeAnimation = p.getBoolean(K_CHARGE, false),
            chargeAlwaysOn = p.getBoolean(K_CHARGE_ALWAYS, false),
        )
        ToolsConfigState.set(cfg)
        return cfg
    }

    fun save(ctx: Context, cfg: ToolsConfig) {
        prefs(ctx).edit()
            .putBoolean(K_KEEP_ON, cfg.keepScreenOn)
            .putBoolean(K_ALWAYS_WAKE, cfg.alwaysWakeUp)
            .putBoolean(K_COVER, cfg.coverDetection)
            .putBoolean(K_CHARGE, cfg.chargeAnimation)
            .putBoolean(K_CHARGE_ALWAYS, cfg.chargeAlwaysOn)
            .apply()
        ToolsConfigState.set(cfg)
    }
}
