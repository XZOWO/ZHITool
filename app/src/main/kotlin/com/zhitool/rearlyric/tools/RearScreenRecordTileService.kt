/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 录屏磁贴参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 RearScreenRecordTileService。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.tools.record.ScreenRecordService

/** 控制中心快捷开关：背屏录屏（显示/收起录屏悬浮窗）。 */
class RearScreenRecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (ScreenRecordService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@unlockAndRun
            }
            if (ScreenRecordService.isRunning) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP),
                )
                setTileState(Tile.STATE_INACTIVE)
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_SHOW),
                )
                setTileState(Tile.STATE_ACTIVE)
            }
        }
    }

    private fun setTileState(state: Int) {
        qsTile?.apply {
            this.state = state
            updateTile()
        }
    }
}
