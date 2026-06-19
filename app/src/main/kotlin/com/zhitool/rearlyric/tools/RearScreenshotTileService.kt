/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 快捷开关截图流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 RearScreenshotTileService。
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

/** 控制中心快捷开关：截取背屏画面保存到相册。 */
class RearScreenshotTileService : TileService() {

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
        thread(name = "zhi-tile-shot") {
            if (!RootShell.available && !RootShell.refresh()) {
                main.post { toast("请先打开 ZHITool 授权 Root") }
                return@thread
            }
            // 先收起控制中心，避免截到下拉面板；再截图。
            RearTools.collapseStatusBar()
            Thread.sleep(250)
            RearTools.takeRearScreenshot()
            main.post { toast("背屏截图已保存到相册") }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
