/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Logic derived from REAREye's RearScreenActivityWhitelistModule
 * (https://github.com/killerprojecte/REAREye), Copyright (C) the REAREye authors, GPL-3.0.
 * Modifications Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.system

import android.util.Log
import com.zhitool.rearlyric.BuildConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Keeps ZHITool's rear activities eligible while the device is locked.
 *
 * Every lookup and every piece of ZHITool-owned reflection is fail-open: a ROM mismatch only
 * disables this hook and never prevents the original system_server method from running.
 */
object RearActivityGuardHook {
    private const val TAG = "ZhiRearActivityGuard"
    private const val ACTIVITY_STARTER_IMPL = "com.android.server.wm.ActivityStarterImpl"
    private const val PREF = "zhi_hook"
    private const val KEY_ALLOW_REAR_ACTIVITY = "allow_rear_activity"
    private const val KEY_SKIP_LOCK_BACK_HOME = "skip_lock_back_home"
    private val APP_PACKAGE = BuildConfig.APPLICATION_ID

    @Volatile
    private var installed = false

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val starterClass = runCatching { classLoader.loadClass(ACTIVITY_STARTER_IMPL) }
            .getOrElse {
                module.log(Log.WARN, TAG, "$ACTIVITY_STARTER_IMPL not found; skip", it)
                return
            }

        runCatching { hookRearActivityChecks(module, starterClass) }
            .onFailure { module.log(Log.ERROR, TAG, "rear activity hook setup failed", it) }
        runCatching { hookTransitionFinished(module, starterClass) }
            .onFailure { module.log(Log.ERROR, TAG, "transition hook setup failed", it) }
    }

    private fun hookRearActivityChecks(module: XposedModule, starterClass: Class<*>) {
        val whitelistField = findField(starterClass, "REAR_SCREEN_METADATA_WHITE_LIST")
            ?.takeIf { Set::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }

        // Inject once when possible, then again immediately before Xiaomi consults the set. The
        // latter also survives ROM code rebuilding/clearing the set after systemReady.
        if (isEnabled(module, KEY_ALLOW_REAR_ACTIVITY, true)) {
            injectSelf(module, whitelistField, null)
        }

        val showChecks = allMethods(starterClass).filter {
            it.name == "isShouldShowOnRearDisplay" && it.returnType == Boolean::class.javaPrimitiveType
        }
        showChecks.forEach { method ->
            installBooleanRearCheck(module, method, whitelistField, injectWhitelist = true)
        }
        if (showChecks.isEmpty()) {
            module.log(Log.WARN, TAG, "isShouldShowOnRearDisplay not found")
        }

        val startChecks = allMethods(starterClass).filter {
            it.name == "isAllowedToStartOnRearDisplay" &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        startChecks.forEach { method ->
            installBooleanRearCheck(module, method, whitelistField, injectWhitelist = false)
        }
        if (startChecks.isEmpty()) {
            module.log(Log.WARN, TAG, "isAllowedToStartOnRearDisplay not found")
        }
    }

    private fun installBooleanRearCheck(
        module: XposedModule,
        method: Method,
        whitelistField: Field?,
        injectWhitelist: Boolean,
    ) {
        runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val enabled = isEnabled(module, KEY_ALLOW_REAR_ACTIVITY, true)
                    if (enabled && injectWhitelist) {
                        injectSelf(module, whitelistField, chain.thisObject)
                    }

                    val original = chain.proceed()
                    if (original != false || !enabled) return original

                    // The metadata set is the primary path. This is a narrow fallback for ROMs
                    // whose second gate ignores that set but exposes an ActivityRecord argument.
                    val isSelf = runCatching {
                        // Xiaomi 的目标 ActivityRecord 是第 1 个参数。只判断目标，不能因
                        // caller/source 参数恰好来自 ZHITool 就放行另一应用。
                        extractPackageName(chain.getArg(0)) == APP_PACKAGE
                    }.onFailure {
                        module.log(Log.WARN, TAG, "package lookup failed; keep original result", it)
                    }.getOrDefault(false)
                    return if (isSelf) true else original
                }
            })
            module.log(Log.INFO, TAG, "hooked ${method.name}/${method.parameterCount}")
        }.onFailure {
            module.log(Log.ERROR, TAG, "hook ${method.name} failed", it)
        }
    }

    private fun hookTransitionFinished(module: XposedModule, starterClass: Class<*>) {
        val methods = allMethods(starterClass).filter {
            it.name == "handlerTransitionFinished" &&
                it.parameterCount > 3 &&
                (it.parameterTypes[3] == Boolean::class.javaPrimitiveType ||
                    it.parameterTypes[3] == Boolean::class.java)
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!isEnabled(module, KEY_SKIP_LOCK_BACK_HOME, false)) {
                            return chain.proceed()
                        }
                        val args = runCatching { chain.args.toTypedArray() }
                            .onFailure {
                                module.log(Log.WARN, TAG, "transition args unavailable; fail-open", it)
                            }
                            .getOrNull()
                            ?: return chain.proceed()
                        if (args.size <= 3 || args[3] !is Boolean) return chain.proceed()
                        args[3] = false
                        return chain.proceed(args)
                    }
                })
                module.log(Log.INFO, TAG, "hooked handlerTransitionFinished/${method.parameterCount}")
            }.onFailure {
                module.log(Log.ERROR, TAG, "hook handlerTransitionFinished failed", it)
            }
        }
        if (methods.isEmpty()) {
            module.log(Log.WARN, TAG, "handlerTransitionFinished(bool arg 4) not found")
        }
    }

    private fun injectSelf(module: XposedModule, field: Field?, receiver: Any?) {
        if (field == null) return
        runCatching {
            val owner = if (Modifier.isStatic(field.modifiers)) null else receiver
            if (!Modifier.isStatic(field.modifiers) && owner == null) return
            @Suppress("UNCHECKED_CAST")
            val whitelist = field.get(owner) as? MutableSet<Any?> ?: return
            whitelist.add(APP_PACKAGE)
        }.onFailure {
            module.log(Log.WARN, TAG, "rear metadata whitelist injection failed; fail-open", it)
        }
    }

    private fun extractPackageName(value: Any?): String? {
        if (value == null) return null
        if (value is String) return value

        val field = findField(value.javaClass, "packageName")
        val fromField = runCatching {
            field?.apply { isAccessible = true }?.get(value) as? String
        }.getOrNull()
        if (!fromField.isNullOrBlank()) return fromField

        val getter = allMethods(value.javaClass).firstOrNull {
            it.name == "getPackageName" && it.parameterCount == 0 && it.returnType == String::class.java
        }
        return runCatching {
            getter?.apply { isAccessible = true }?.invoke(value) as? String
        }.getOrNull()
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun allMethods(type: Class<*>): List<Method> {
        val result = LinkedHashMap<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.forEach { method ->
                val key = method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") {
                    it.name
                }
                result.putIfAbsent(key, method)
            }
            current = current.superclass
        }
        return result.values.toList()
    }

    private fun isEnabled(module: XposedModule, key: String, default: Boolean): Boolean =
        runCatching { module.getRemotePreferences(PREF).getBoolean(key, default) }
            .getOrDefault(false)
}
