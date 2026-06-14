package com.zhitool.rearlyric.rear

import android.graphics.Rect
import android.util.Log
import com.zhitool.rearlyric.core.RootShell

object RearDisplayHelper {
    private const val TAG = "ZhiRearDisplayHelper"

    data class RearDisplayInfo(
        val width: Int = 1200,
        val height: Int = 2200,
        val densityDpi: Int = 440,
        val cutout: Rect = Rect(),
    ) {
        fun hasCutout(): Boolean =
            cutout.left > 0 || cutout.top > 0 || cutout.right > 0 || cutout.bottom > 0
    }

    @Volatile
    private var cachedInfo: RearDisplayInfo? = null

    fun getRearDisplayInfo(forceRefresh: Boolean = false): RearDisplayInfo {
        if (!forceRefresh) {
            cachedInfo?.let { return it }
        }
        val info = runCatching {
            val output = RootShell.exec("dumpsys display").output
            if (output.isBlank()) RearDisplayInfo() else parseRearDisplayInfo(output)
        }.getOrElse {
            Log.w(TAG, "parse rear display info failed: ${it.message}")
            RearDisplayInfo()
        }
        cachedInfo = info
        return info
    }

    private fun parseRearDisplayInfo(dumpsys: String): RearDisplayInfo {
        var width = 1200
        var height = 2200
        var densityDpi = 440

        val viewport = Regex("displayId=1[^}]*deviceWidth=(\\d+),\\s*deviceHeight=(\\d+)")
            .find(dumpsys)
        if (viewport != null) {
            width = viewport.groupValues[1].toIntOrNull() ?: width
            height = viewport.groupValues[2].toIntOrNull() ?: height
        }

        val uniqueId = Regex("displayId=1[^}]*uniqueId='([^']+)'")
            .find(dumpsys)
            ?.groupValues
            ?.getOrNull(1)

        val blocks = dumpsys.split("DisplayDeviceInfo")
        val display1Block = blocks.firstOrNull { block ->
            (uniqueId != null && block.contains(uniqueId)) ||
                block.contains("$width x $height")
        }.orEmpty()

        if (display1Block.isNotBlank()) {
            densityDpi = Regex("density\\s+(\\d+)")
                .find(display1Block)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: densityDpi
        }

        val cutout = parseCutout(display1Block)
        return RearDisplayInfo(
            width = width,
            height = height,
            densityDpi = densityDpi,
            cutout = cutout,
        )
    }

    private fun parseCutout(display1Block: String): Rect {
        if (display1Block.isBlank()) return Rect()

        val miui = Regex("DisplayCutout\\{insets=Rect\\((\\d+),\\s*(\\d+)\\s*-\\s*(\\d+),\\s*(\\d+)\\)")
            .find(display1Block)
        if (miui != null) {
            return Rect(
                miui.groupValues[1].toIntOrNull() ?: 0,
                miui.groupValues[2].toIntOrNull() ?: 0,
                miui.groupValues[3].toIntOrNull() ?: 0,
                miui.groupValues[4].toIntOrNull() ?: 0,
            )
        }

        val standard = Regex("DisplayCutout\\{insets=Rect\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)")
            .find(display1Block)
        if (standard != null) {
            return Rect(
                standard.groupValues[1].toIntOrNull() ?: 0,
                standard.groupValues[2].toIntOrNull() ?: 0,
                standard.groupValues[3].toIntOrNull() ?: 0,
                standard.groupValues[4].toIntOrNull() ?: 0,
            )
        }

        return Rect()
    }
}
