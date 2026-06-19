/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 快捷开关切换流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 SwitchToRearTileService。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.zhitool.rearlyric.core.RearTools
import com.zhitool.rearlyric.core.RootShell
import kotlin.concurrent.thread

/** 控制中心快捷开关：把当前前台应用切换到背屏。 */
class SwitchToRearTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = null
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        feedback("切换中...")
        thread(name = "zhi-tile-switch") {
            if (!RootShell.available && !RootShell.refresh()) {
                main.post { toast("请先打开 ZHITool 授权 Root"); feedback(null) }
                return@thread
            }
            val app = RearTools.switchCurrentAppToRear()
            RearTools.collapseStatusBar()
            if (app != null) {
                RearToolsService.startKeeper(applicationContext, app)
                main.post { toast("${label(app.packageName)} 已投到背屏"); feedback(null) }
            } else {
                main.post { toast("切换失败"); feedback(null) }
            }
        }
    }

    private fun feedback(text: String?) {
        main.post {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = text
                updateTile()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
