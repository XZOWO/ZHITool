/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.charge

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.zhitool.rearlyric.tools.ToolsConfigState
import com.zhitool.rearlyric.tools.overlay.RearOverlaySupport
import kotlin.concurrent.thread

/** 充电动画的投影/收回（被 RearToolsService 的运行时电源接收器调用）。 */
object ChargeOverlay {
    private const val ACTIVITY = "com.zhitool.rearlyric/.tools.charge.RearChargingActivity"
    private const val COOLDOWN_MS = 6000L

    @Volatile private var lastProjectAt = 0L

    fun project(context: Context) {
        if (!ToolsConfigState.current.chargeAnimation) return
        val now = System.currentTimeMillis()
        if (now - lastProjectAt < COOLDOWN_MS) return
        lastProjectAt = now
        val level = batteryLevel(context)
        val alwaysOn = ToolsConfigState.current.chargeAlwaysOn
        thread(name = "zhi-charge-project") {
            RearOverlaySupport.project(
                "am start -n $ACTIVITY --ei ${RearChargingActivity.EXTRA_LEVEL} $level " +
                    "--ez ${RearChargingActivity.EXTRA_ALWAYS_ON} $alwaysOn",
                "RearChargingActivity",
            )
        }
    }

    fun finish(context: Context) {
        context.sendBroadcast(
            Intent(RearChargingActivity.ACTION_FINISH).setPackage(context.packageName),
        )
    }

    fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }

    private fun batteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
    }
}
