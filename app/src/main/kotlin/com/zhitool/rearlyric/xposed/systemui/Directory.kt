package com.zhitool.rearlyric.xposed.systemui

import android.content.Context
import com.zhitool.rearlyric.lyric.CoverStorage
import java.io.File

object Directory {
    private lateinit var moduleContext: Context

    fun initialize(hostContext: Context) {
        moduleContext = CoverStorage.moduleContext(hostContext) ?: hostContext
        CoverStorage.rootDir(moduleContext)
    }

    fun getPackageDataDir(packageName: String): File? {
        if (!::moduleContext.isInitialized) return null
        return CoverStorage.packageDir(moduleContext, packageName)
    }
}
