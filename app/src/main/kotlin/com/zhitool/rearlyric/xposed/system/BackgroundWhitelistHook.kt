/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Logic derived from REAREye's BackgroundWhitelistModule
 * (https://github.com/killerprojecte/REAREye), Copyright (C) the REAREye authors, GPL-3.0.
 * Modifications Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.system

import android.content.Context
import android.util.Log
import com.zhitool.rearlyric.BuildConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 「后台保活」hook（对齐 REAREye BackgroundWhitelistModule），运行在 system_server。
 *
 * MIUI/HyperOS 的 `com.android.server.am.ProcessPolicy` 维护进程动态白名单与锁定态。
 * 把本应用注入其中，避免歌词服务/进程在后台被清理（与子屏不返回桌面一起，构成
 * REAREye 那套「投到背屏还能正常跑」的保活组合）。
 *
 * ⚠️ 这是 system_server 内的 hook，任何抛出都可能拖垮系统进程导致 bootloop。
 * 因此：类/方法找不到一律 no-op；拦截里**先执行原方法**再在 try/catch 中注入，
 * 注入失败绝不向 system_server 抛出。
 */
object BackgroundWhitelistHook {
    private const val TAG = "ZhiBgWhitelist"
    private const val PROCESS_POLICY = "com.android.server.am.ProcessPolicy"
    private const val PREF = "zhi_hook"
    private const val KEY_ENABLED = "keep_background"

    /** 注入白名单/锁定态的包：本应用。 */
    private val APPS = listOf(BuildConfig.APPLICATION_ID)

    /** REAREye 同款用户标识占位（updateApplicationLockedState 的 userId 参数）。 */
    private const val LOCK_USER_ID = -100

    @Volatile
    private var installed = false

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val ppClass = runCatching { classLoader.loadClass(PROCESS_POLICY) }.getOrNull()
        if (ppClass == null) {
            module.log(Log.WARN, TAG, "$PROCESS_POLICY not found, skip (non-MIUI?)")
            return
        }

        hookUpdateDynamicWhiteList(module, ppClass)
        hookSystemReady(module, ppClass)
    }

    /** updateDynamicWhiteList(Context, int): HashMap<String,Boolean> —— after 注入本包=true。 */
    private fun hookUpdateDynamicWhiteList(module: XposedModule, ppClass: Class<*>) {
        val method = runCatching {
            ppClass.getDeclaredMethod(
                "updateDynamicWhiteList",
                Context::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "updateDynamicWhiteList not found")
            return
        }
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        if (isEnabled(module)) {
                            @Suppress("UNCHECKED_CAST")
                            val map = result as? HashMap<String, Boolean>
                            if (map != null) APPS.forEach { map[it] = true }
                        }
                    } catch (t: Throwable) {
                        module.log(Log.ERROR, TAG, "inject whitelist failed", t)
                    }
                    return result
                }
            })
            module.log(Log.INFO, TAG, "hooked updateDynamicWhiteList")
        }.onFailure { module.log(Log.ERROR, TAG, "hook updateDynamicWhiteList failed", it) }
    }

    /** systemReady(Context): void —— after 调 updateApplicationLockedState 锁定本包。 */
    private fun hookSystemReady(module: XposedModule, ppClass: Class<*>) {
        val method = runCatching {
            ppClass.getDeclaredMethod("systemReady", Context::class.java)
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "systemReady not found")
            return
        }
        val lockMethod: Method? = runCatching {
            ppClass.getDeclaredMethod(
                "updateApplicationLockedState",
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()

        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        if (isEnabled(module) && lockMethod != null) {
                            APPS.forEach { lockMethod.invoke(chain.thisObject, it, LOCK_USER_ID, true) }
                        }
                    } catch (t: Throwable) {
                        module.log(Log.ERROR, TAG, "lock app state failed", t)
                    }
                    return result
                }
            })
            module.log(Log.INFO, TAG, "hooked systemReady (lockMethod=${lockMethod != null})")
        }.onFailure { module.log(Log.ERROR, TAG, "hook systemReady failed", it) }
    }

    private fun isEnabled(module: XposedModule): Boolean =
        runCatching {
            module.getRemotePreferences(PREF).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(true)
}
