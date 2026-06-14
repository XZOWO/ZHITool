package com.zhitool.rearlyric.xposed

import android.util.Log
import androidx.annotation.Keep
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.xposed.subscreen.SubScreenHomeGuard
import com.zhitool.rearlyric.xposed.system.BackgroundWhitelistHook
import com.zhitool.rearlyric.xposed.systemui.SystemUIHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@Keep
class ModuleEntry : XposedModule() {

    companion object {
        lateinit var instance: ModuleEntry
            private set

        private const val SUBSCREEN_PKG = "com.xiaomi.subscreencenter"

        private val scopes = setOf(
            BuildConfig.APPLICATION_ID,
            "com.android.systemui",
            SUBSCREEN_PKG,
        )
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        Log.i("ZhiModuleEntry", "module loaded in ${param.processName}")
    }

    /**
     * system_server 启动：装「后台保活」hook（ProcessPolicy 注入动态白名单）。
     * 这是系统进程内的 hook，[BackgroundWhitelistHook] 内部已做满防御，外层再兜一层。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        Log.i("ZhiModuleEntry", "system server starting")
        runCatching { BackgroundWhitelistHook.hook(this, param.classLoader) }
            .onFailure { Log.e("ZhiModuleEntry", "system server hook failed", it) }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName !in scopes) return
        Log.i("ZhiModuleEntry", "package loaded: ${param.packageName}")
        when (param.packageName) {
            "com.android.systemui" -> SystemUIHooker.hook(this, param)
            SUBSCREEN_PKG -> runCatching { SubScreenHomeGuard.hook(this, param) }
                .onFailure { Log.e("ZhiModuleEntry", "subscreen guard hook failed", it) }
        }
    }
}
