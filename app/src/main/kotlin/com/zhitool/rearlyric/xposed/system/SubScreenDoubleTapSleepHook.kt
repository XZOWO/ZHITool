/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Logic derived from REAREye's DisableSubScreenDoubleTapSleepHook
 * (https://github.com/killerprojecte/REAREye), Copyright (C) the REAREye authors, GPL-3.0.
 * Modifications Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.system

import android.util.Log
import com.zhitool.rearlyric.BuildConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Blocks Xiaomi's rear-screen double-tap-to-sleep gesture only while ZHITool has focus. */
object SubScreenDoubleTapSleepHook {
    private const val TAG = "ZhiRearDoubleTap"
    private const val GESTURE_CLASS =
        "com.miui.server.input.gesture.multifingergesture.gesture.MiuiSubscreenDoubleTapGesture"
    private const val MANAGER_CLASS =
        "com.miui.server.input.gesture.multifingergesture.MiuiSubScreenMultiFingerGestureManager"
    private const val PREF = "zhi_hook"
    private const val KEY_ENABLED = "disable_double_tap_sleep"
    private val APP_PACKAGE = BuildConfig.APPLICATION_ID

    @Volatile
    private var installed = false

    @Volatile
    private var focusedPackageName: String? = null

    @Volatile
    private var managerInstance: Any? = null

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val gestureClass = runCatching { classLoader.loadClass(GESTURE_CLASS) }
            .getOrElse {
                module.log(Log.WARN, TAG, "$GESTURE_CLASS not found; skip", it)
                return
            }
        val managerClass = runCatching { classLoader.loadClass(MANAGER_CLASS) }
            .onFailure { module.log(Log.WARN, TAG, "$MANAGER_CLASS not found; no focus source", it) }
            .getOrNull()

        if (managerClass != null) {
            runCatching { hookFocusUpdates(module, managerClass) }
                .onFailure { module.log(Log.ERROR, TAG, "focus hook setup failed", it) }
        }
        runCatching { hookPointerEvents(module, gestureClass, managerClass) }
            .onFailure { module.log(Log.ERROR, TAG, "pointer hook setup failed", it) }
    }

    private fun hookFocusUpdates(module: XposedModule, managerClass: Class<*>) {
        val methods = allMethods(managerClass).filter {
            it.name == "onFocusedWindowChanged" && it.parameterCount == 3
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            managerInstance = chain.thisObject
                            // Clear the cache as well when Xiaomi reports a null/unresolvable focus;
                            // retaining the old ZHITool package would block gestures in other apps.
                            focusedPackageName = owningPackage(chain.getArg(2))
                        }.onFailure {
                            focusedPackageName = null
                            module.log(Log.WARN, TAG, "focused package update failed; fail-open", it)
                        }
                        return result
                    }
                })
                module.log(Log.INFO, TAG, "hooked onFocusedWindowChanged/${method.parameterCount}")
            }.onFailure {
                module.log(Log.ERROR, TAG, "hook onFocusedWindowChanged failed", it)
            }
        }
        if (methods.isEmpty()) module.log(Log.WARN, TAG, "onFocusedWindowChanged/3 not found")
    }

    private fun hookPointerEvents(
        module: XposedModule,
        gestureClass: Class<*>,
        managerClass: Class<*>?,
    ) {
        val methods = allMethods(gestureClass).filter {
            it.name == "onPointerEvent" &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].name == "android.view.MotionEvent"
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!isEnabled(module)) return chain.proceed()

                        val focused = runCatching {
                            focusedPackageName ?: resolveFocusedPackage(managerClass)?.also {
                                focusedPackageName = it
                            }
                        }.onFailure {
                            module.log(Log.WARN, TAG, "focused package lookup failed; fail-open", it)
                        }.getOrNull()

                        if (focused == APP_PACKAGE) {
                            module.log(Log.DEBUG, TAG, "block rear double-tap sleep for $focused")
                            return null
                        }
                        return chain.proceed()
                    }
                })
                module.log(Log.INFO, TAG, "hooked onPointerEvent/${method.parameterCount}")
            }.onFailure {
                module.log(Log.ERROR, TAG, "hook onPointerEvent failed", it)
            }
        }
        if (methods.isEmpty()) module.log(Log.WARN, TAG, "onPointerEvent(MotionEvent) not found")
    }

    private fun resolveFocusedPackage(managerClass: Class<*>?): String? {
        managerClass ?: return null
        val getter = allMethods(managerClass).firstOrNull {
            it.name == "getFocusedWindow" && it.parameterCount == 0
        } ?: return null
        getter.isAccessible = true
        val receiver = if (Modifier.isStatic(getter.modifiers)) null else managerInstance ?: return null
        return owningPackage(getter.invoke(receiver))
    }

    private fun owningPackage(window: Any?): String? {
        window ?: return null
        val method = allMethods(window.javaClass).firstOrNull {
            it.name == "getOwningPackage" &&
                it.parameterCount == 0 &&
                it.returnType == String::class.java
        } ?: return null
        method.isAccessible = true
        return method.invoke(window) as? String
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

    private fun isEnabled(module: XposedModule): Boolean =
        runCatching { module.getRemotePreferences(PREF).getBoolean(KEY_ENABLED, true) }
            .getOrDefault(false)
}
