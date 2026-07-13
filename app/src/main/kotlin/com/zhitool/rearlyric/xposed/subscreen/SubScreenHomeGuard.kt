/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Logic derived from REAREye's SubScreenBackHomeWhitelistModule
 * (https://github.com/killerprojecte/REAREye), Copyright (C) the REAREye authors, GPL-3.0.
 * Modifications Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.subscreen

import android.util.Log
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.xposed.DexKitNative
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 「锁屏背屏不返回桌面」hook（对齐 REAREye SubScreenBackHomeWhitelistModule）。
 *
 * 息屏/进入 AOD 时，com.xiaomi.subscreencenter 会调用一个内部方法把 SubScreenLauncher
 * 拉到副屏(display 1)前台（日志 "Start SubScreen Home reason aod"），这会把我们正在
 * 副屏渲染的歌词 Activity 顶掉、stop 掉，于是歌词"显示但不动"。本 hook 在该方法上拦截：
 * 当 reason==aod 且副屏前台是受保护应用（本歌词页所属包）时，跳过原方法 → 不返回桌面，
 * 歌词 Activity 保持前台、渲染链路不被打断。
 *
 * 方法/字段在 subscreencenter 中被混淆，用 DexKit 按稳定的字符串特征定位（同 REAREye）。
 * Hook 始终安装、每次调用时读取开关；若前台包字段定位或读取失败则直接放行原方法，
 * 避免 ROM 版本差异误拦其它背屏应用。
 */
object SubScreenHomeGuard {
    private const val TAG = "ZhiSubScreenGuard"
    private const val SUBSCREEN_PKG = "com.xiaomi.subscreencenter"
    private const val AOD_REASON = "aod"
    private const val PREF = "zhi_hook"
    private const val KEY_ENABLED = "guard_subscreen_home"

    /** 受保护包：本应用（背屏歌词 Activity 所属包）。 */
    private val PROTECTED = setOf(BuildConfig.APPLICATION_ID)

    @Volatile
    private var installed = false

    fun hook(module: XposedModule, param: PackageLoadedParam) {
        if (installed) return
        installed = true
        val classLoader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir ?: run {
            module.log(Log.WARN, TAG, "subscreencenter sourceDir null, abort")
            return
        }
        // DexKit 解析较重，而息屏返回桌面发生在 App 启动很久之后，放后台线程做即可。
        Thread {
            runCatching { resolveAndHook(module, classLoader, apkPath) }
                .onFailure { module.log(Log.ERROR, TAG, "resolveAndHook failed", it) }
        }.apply {
            name = "zhi-subscreen-dexkit"
            isDaemon = true
        }.start()
    }

    private fun resolveAndHook(module: XposedModule, classLoader: ClassLoader, apkPath: String) {
        if (!DexKitNative.ensureLoaded(module)) {
            module.log(Log.ERROR, TAG, "libdexkit not loaded, abort")
            return
        }

        DexKitBridge.create(apkPath).use { bridge ->
            val methodData = bridge.findMethod {
                searchPackages(SUBSCREEN_PKG)
                matcher {
                    paramTypes("java.lang.String")
                    returnType = "void"
                    usingStrings(
                        "Start SubScreen Home reason ",
                        "getHomeToFrontOptions from Aod or turning off",
                    )
                }
            }.singleOrNull()

            if (methodData == null) {
                module.log(Log.WARN, TAG, "home-to-front method not resolved (version mismatch?)")
                return
            }

            val className = methodData.className
            val methodName = methodData.methodName
            val reflectMethod = methodData.getMethodInstance(classLoader)

            // 前台包字段：同类中、被 home-to-front 读取的 String 字段（外科式白名单用）。
            val reflectField = runCatching {
                bridge.findField {
                    searchPackages(className.substringBeforeLast('.'))
                    matcher {
                        declaredClass = className
                        type = "java.lang.String"
                        readMethods {
                            add {
                                declaredClass = className
                                name = methodName
                                paramTypes("java.lang.String")
                                returnType = "void"
                                usingStrings("Start SubScreen Home reason ")
                            }
                        }
                    }
                }.singleOrNull()?.getFieldInstance(classLoader)?.apply { isAccessible = true }
            }.getOrNull()

            module.log(
                Log.INFO,
                TAG,
                "resolved $className.$methodName field=${reflectField?.name ?: "<none, fail-open>"}",
            )
            installHook(module, reflectMethod, reflectField)
        }
    }

    private fun installHook(module: XposedModule, method: Method, field: Field?) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val shouldBlock = runCatching {
                    val reason = chain.getArg(0) as? String
                    if (reason != AOD_REASON || !isEnabled(module) || field == null) {
                        return@runCatching false
                    }
                    val foreground = field.get(chain.thisObject) as? String
                        ?: return@runCatching false
                    foreground in PROTECTED
                }.onFailure {
                    module.log(Log.ERROR, TAG, "guard decision failed; fail-open", it)
                }.getOrDefault(false)

                if (!shouldBlock) return chain.proceed()
                module.log(Log.INFO, TAG, "block subscreen home on aod")
                return null
            }
        })
        module.log(Log.INFO, TAG, "subscreen home guard installed")
    }

    private fun isEnabled(module: XposedModule): Boolean =
        runCatching {
            module.getRemotePreferences(PREF).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(false)
}
