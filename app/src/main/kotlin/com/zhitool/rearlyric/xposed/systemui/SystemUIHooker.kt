package com.zhitool.rearlyric.xposed.systemui

import android.util.Log
import com.zhitool.rearlyric.xposed.PackageHooker

object SystemUIHooker : PackageHooker() {
    override fun onHook() {
        if (!isMainProcess()) return
        doOnAppCreated { app ->
            Log.i("ZhiSystemUIHooker", "SystemUI created: ${app.packageName}")
            Directory.initialize(app)
            SystemUIMediaUtils.init(app)
            NotificationCoverHelper.initialize(app)
        }
    }
}
