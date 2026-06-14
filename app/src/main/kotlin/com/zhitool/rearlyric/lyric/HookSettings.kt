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

    data class State(
        val guardSubScreenHome: Boolean = true,
        val keepBackground: Boolean = true,
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
        )
    }

    fun save(context: Context, state: State) {
        _flow.value = state
        prefs(context).edit().apply {
            putBoolean(K_GUARD_SUBSCREEN_HOME, state.guardSubScreenHome)
            putBoolean(K_KEEP_BACKGROUND, state.keepBackground)
            apply()
        }
    }
}
