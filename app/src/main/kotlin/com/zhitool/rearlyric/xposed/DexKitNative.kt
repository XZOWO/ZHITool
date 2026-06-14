package com.zhitool.rearlyric.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * DexKit 2.x 不再自带 `System.loadLibrary("dexkit")`，必须消费方在调用 `DexKitBridge.create`
 * 之前自行加载 libdexkit.so。在被 hook 的目标进程（如 subscreencenter）里，"dexkit" 这个名字
 * 要靠**模块自己的 classloader 的 native 搜索路径**解析——因为 DexKit 类打在我们模块 APK 里、
 * 由模块 classloader 加载（与目标 App 的 lib 路径无关）。
 *
 * 优先用标准 [System.loadLibrary]；失败则退回用模块 nativeLibraryDir 的绝对路径 [System.load]。
 */
object DexKitNative {
    private const val TAG = "ZhiDexKitNative"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(module: XposedModule): Boolean {
        if (loaded) return true

        runCatching {
            System.loadLibrary("dexkit")
            loaded = true
        }.onSuccess {
            module.log(Log.INFO, TAG, "loaded libdexkit via loadLibrary")
            return true
        }

        runCatching {
            val dir = module.moduleApplicationInfo.nativeLibraryDir
            System.load("$dir/libdexkit.so")
            loaded = true
            module.log(Log.INFO, TAG, "loaded libdexkit via absolute path $dir")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to load libdexkit.so", it)
        }
        return loaded
    }
}
