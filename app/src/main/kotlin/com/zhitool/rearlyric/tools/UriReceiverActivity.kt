/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * URI 调用流程参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 UriCommandService。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.zhitool.rearlyric.core.RearTools
import com.zhitool.rearlyric.core.RootShell
import kotlin.concurrent.thread

/**
 * 透明 URI 接收页：支持 `zhitool://` 与 `mrss://`（兼容 MRSS 用户的旧自动化脚本）。
 *
 * - `…://switch?current=1`            切换当前前台应用到背屏
 * - `…://switch?packageName=<pkg>`    启动指定应用并投到背屏
 * - `…://return?current=1`            把背屏应用拉回主屏
 * - `…://screenshot`                  背屏截图
 * - `…://config?dpi=<n>&rotation=<0-3>` 设置背屏 DPI / 旋转
 */
class UriReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (uri != null && (uri.scheme == "zhitool" || uri.scheme == "mrss")) {
            val appCtx = applicationContext
            thread(name = "zhi-uri") { dispatch(appCtx, uri) }
        }
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun dispatch(ctx: Context, uri: Uri) {
        if (!RootShell.available && !RootShell.refresh()) {
            Log.w(TAG, "root unavailable, ignore $uri")
            return
        }
        when (uri.host) {
            "switch" -> {
                applyConfig(uri)
                val current = uri.getQueryParameter("current")
                val pkg = uri.getQueryParameter("packageName")
                val app = when {
                    current == "1" || current.equals("true", true) -> RearTools.switchCurrentAppToRear()
                    pkg != null -> launchAndMove(pkg)
                    else -> null
                }
                if (app != null) RearToolsService.startKeeper(ctx, app)
            }
            "return" -> {
                RearTools.returnRearToMain()
                RearToolsService.stopKeeper(ctx)
            }
            "screenshot" -> RearTools.takeRearScreenshot()
            "config" -> applyConfig(uri)
        }
    }

    private fun applyConfig(uri: Uri) {
        uri.getQueryParameter("dpi")?.toIntOrNull()?.let { RearTools.setRearDpi(it) }
        uri.getQueryParameter("rotation")?.toIntOrNull()?.let { RearTools.setRearRotation(it) }
    }

    /** 启动指定包名的主 Activity，等它到前台后移到背屏。 */
    private fun launchAndMove(pkg: String): RearTools.AppTask? {
        RearTools.disableSubScreenLauncher()
        RootShell.run("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg")
        repeat(4) { attempt ->
            Thread.sleep(500L + attempt * 200L)
            val fg = RearTools.getCurrentForegroundApp()
            if (fg != null && fg.packageName == pkg) {
                return if (RearTools.moveTaskToDisplay(fg.taskId, RearTools.REAR_DISPLAY_ID)) {
                    RearTools.wakeRear(); fg
                } else null
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ZhiUriReceiver"
    }
}
