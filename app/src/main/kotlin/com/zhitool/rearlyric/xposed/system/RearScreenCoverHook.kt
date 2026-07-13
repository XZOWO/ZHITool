/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Logic derived from REAREye's DisableRearScreenCoverHook
 * (https://github.com/killerprojecte/REAREye), Copyright (C) the REAREye authors, GPL-3.0.
 * Modifications Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.system

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** Optionally suppresses Xiaomi's lock-screen cover view on display 1. */
object RearScreenCoverHook {
    private const val TAG = "ZhiRearScreenCover"
    private const val COVER_MANAGER = "com.android.server.power.DualScreenCoverManager"
    private const val PREF = "zhi_hook"
    private const val KEY_ENABLED = "disable_rear_screen_cover"
    private const val REAR_DISPLAY_ID = 1

    @Volatile
    private var installed = false

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val managerClass = runCatching { classLoader.loadClass(COVER_MANAGER) }
            .getOrElse {
                module.log(Log.WARN, TAG, "$COVER_MANAGER not found; skip", it)
                return
            }
        val methods = managerClass.declaredMethods.filter {
            it.name == "showCoverView" &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val displayId = runCatching { chain.getArg(0) as? Int }
                            .onFailure {
                                module.log(Log.WARN, TAG, "display id unavailable; fail-open", it)
                            }
                            .getOrNull()
                        if (displayId == REAR_DISPLAY_ID && isEnabled(module)) {
                            module.log(Log.DEBUG, TAG, "block rear cover view on display 1")
                            return null
                        }
                        return chain.proceed()
                    }
                })
                module.log(Log.INFO, TAG, "hooked showCoverView(int)")
            }.onFailure {
                module.log(Log.ERROR, TAG, "hook showCoverView failed", it)
            }
        }
        if (methods.isEmpty()) module.log(Log.WARN, TAG, "showCoverView(int) not found")
    }

    private fun isEnabled(module: XposedModule): Boolean =
        runCatching { module.getRemotePreferences(PREF).getBoolean(KEY_ENABLED, false) }
            .getOrDefault(false)
}
