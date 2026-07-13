package com.zhitool.rearlyric.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LSPosed hook 开关存储。
 *
 * 写在 **MODE_WORLD_READABLE** 的独立 prefs（配合 manifest `xposedsharedprefs=true`），
 * 这样 hook 进程（system_server / subscreencenter）能通过 `module.getRemotePreferences("zhi_hook")`
 * 读到。键名与 [com.zhitool.rearlyric.xposed.subscreen.SubScreenHomeGuard] /
 * [com.zhitool.rearlyric.xposed.system.BackgroundWhitelistHook] 对齐。
 */
object HookSettings {
    const val PREF = "zhi_hook"
    const val K_GUARD_SUBSCREEN_HOME = "guard_subscreen_home"
    const val K_KEEP_BACKGROUND = "keep_background"
    const val K_SKIP_LOCK_BACK_HOME = "skip_lock_back_home"
    const val K_ALLOW_REAR_ACTIVITY = "allow_rear_activity"
    const val K_DISABLE_DOUBLE_TAP_SLEEP = "disable_double_tap_sleep"
    const val K_DISABLE_REAR_SCREEN_COVER = "disable_rear_screen_cover"

    data class State(
        val guardSubScreenHome: Boolean = true,
        val keepBackground: Boolean = true,
        val skipLockBackHome: Boolean = false,
        val allowRearActivity: Boolean = true,
        val disableDoubleTapSleep: Boolean = true,
        val disableRearScreenCover: Boolean = false,
    )

    private val _flow = MutableStateFlow(State())
    val flow: StateFlow<State> = _flow

    val current: State get() = _flow.value

    @SuppressLint("WorldReadableFiles")
    private fun prefs(context: Context): SharedPreferences =
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            // 非 LSPosed 环境（WORLD_READABLE 会抛 SecurityException）退回私有，仅本进程可读。
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }

    fun load(context: Context) {
        val sp = prefs(context)
        _flow.value = State(
            guardSubScreenHome = sp.getBoolean(K_GUARD_SUBSCREEN_HOME, true),
            keepBackground = sp.getBoolean(K_KEEP_BACKGROUND, true),
            skipLockBackHome = sp.getBoolean(K_SKIP_LOCK_BACK_HOME, false),
            allowRearActivity = sp.getBoolean(K_ALLOW_REAR_ACTIVITY, true),
            disableDoubleTapSleep = sp.getBoolean(K_DISABLE_DOUBLE_TAP_SLEEP, true),
            disableRearScreenCover = sp.getBoolean(K_DISABLE_REAR_SCREEN_COVER, false),
        )
    }

    fun save(context: Context, state: State) {
        _flow.value = state
        prefs(context).edit().apply {
            putBoolean(K_GUARD_SUBSCREEN_HOME, state.guardSubScreenHome)
            putBoolean(K_KEEP_BACKGROUND, state.keepBackground)
            putBoolean(K_SKIP_LOCK_BACK_HOME, state.skipLockBackHome)
            putBoolean(K_ALLOW_REAR_ACTIVITY, state.allowRearActivity)
            putBoolean(K_DISABLE_DOUBLE_TAP_SLEEP, state.disableDoubleTapSleep)
            putBoolean(K_DISABLE_REAR_SCREEN_COVER, state.disableRearScreenCover)
            apply()
        }
    }
}
