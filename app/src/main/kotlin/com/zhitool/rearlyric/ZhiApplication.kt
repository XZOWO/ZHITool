package com.zhitool.rearlyric

import android.app.Application
import android.util.Log
import com.zhitool.rearlyric.core.MediaControl
import com.zhitool.rearlyric.lyric.ConfigStore
import com.zhitool.rearlyric.lyric.HookSettings
import com.zhitool.rearlyric.tools.RearToolsService
import com.zhitool.rearlyric.tools.ToolsConfigStore
import com.zhitool.rearlyric.tools.notify.NotifyConfigStore
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        MediaControl.initialize(this)
        ConfigStore.load(this)
        HookSettings.load(this)
        ToolsConfigStore.load(this)
        NotifyConfigStore.load(this)
        RearToolsService.syncFromConfig(this)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i("ZhiApplication", "XposedService bind")
                _lsposedActive.value = true
            }

            override fun onServiceDied(service: XposedService) {
                Log.i("ZhiApplication", "XposedService died")
                _lsposedActive.value = false
            }
        })
    }

    companion object {
        @Volatile
        lateinit var instance: ZhiApplication
            private set

        private val _lsposedActive = MutableStateFlow(false)
        val lsposedActive: StateFlow<Boolean> = _lsposedActive

        fun isLsposedActive(): Boolean = _lsposedActive.value
    }
}
