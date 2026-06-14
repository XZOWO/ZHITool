package com.zhitool.rearlyric

import android.app.Application
import android.util.Log
import com.zhitool.rearlyric.lyric.ConfigStore
import com.zhitool.rearlyric.lyric.HookSettings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ConfigStore.load(this)
        HookSettings.load(this)
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
