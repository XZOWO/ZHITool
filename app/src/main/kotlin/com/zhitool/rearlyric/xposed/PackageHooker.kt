package com.zhitool.rearlyric.xposed

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

abstract class PackageHooker {
    protected lateinit var module: XposedModule
    protected lateinit var packageParam: XposedModuleInterface.PackageLoadedParam

    val packageName: String get() = packageParam.packageName
    val classLoader: ClassLoader get() = packageParam.defaultClassLoader

    @Volatile
    var appContext: Application? = null
        private set

    private val appOnCreateListeners = CopyOnWriteArraySet<(Application) -> Unit>()
    private val hookInstalled = AtomicBoolean(false)
    private var attached = false

    fun isMainProcess(): Boolean =
        packageParam.applicationInfo.processName == packageName

    fun doOnAppCreated(callback: (Application) -> Unit) {
        val current = appContext
        if (current != null) {
            callback(current)
            return
        }
        appOnCreateListeners.add(callback)
        if (hookInstalled.compareAndSet(false, true)) {
            hookApplicationOnCreate()
        }
    }

    private fun hookApplicationOnCreate() {
        val targetClassName = packageParam.applicationInfo.className ?: "android.app.Application"
        try {
            val targetClass = classLoader.loadClass(targetClassName)
            val onCreate = targetClass.getDeclaredMethod("onCreate")
            module.hook(onCreate).intercept(Hooker())
        } catch (t: Throwable) {
            Log.w("ZhiPackageHooker", "hook $targetClassName failed, fallback", t)
            fallbackToGlobalHook()
        }
    }

    private fun fallbackToGlobalHook() {
        try {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            module.hook(onCreate).intercept { chain ->
                chain.proceed()
                handleApplication(chain.thisObject as? Application)
                null
            }
        } catch (t: Throwable) {
            Log.e("ZhiPackageHooker", "global application hook failed", t)
        }
    }

    private inner class Hooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            chain.proceed()
            handleApplication(chain.thisObject as? Application)
            return null
        }
    }

    private fun handleApplication(application: Application?) {
        if (application == null || appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = application
            appOnCreateListeners.forEach { listener ->
                runCatching { listener(application) }
                    .onFailure { Log.e("ZhiPackageHooker", "app callback failed", it) }
            }
            appOnCreateListeners.clear()
        }
    }

    fun hook(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (attached) return
        attached = true
        this.module = module
        this.packageParam = param
        onHook()
    }

    protected abstract fun onHook()
}
