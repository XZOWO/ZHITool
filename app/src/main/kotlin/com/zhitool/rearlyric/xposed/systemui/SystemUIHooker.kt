package com.zhitool.rearlyric.xposed.systemui

import android.content.Context
import android.util.Log
import com.zhitool.rearlyric.xposed.PackageHooker
import io.github.proify.lyricon.central.BridgeCentral

object SystemUIHooker : PackageHooker() {
    private const val TAG = "ZhiSystemUIHooker"

    /** 词幕(lyricon)宿主包：装了它就让它在 SystemUI 托管 central；没装才由 ZHITool 自己托管。 */
    private val LYRICON_HOST_PACKAGES = listOf(
        "io.github.proify.lyricon",
        "io.github.proify.lyricon.core",
    )

    override fun onHook() {
        if (!isMainProcess()) return
        SystemUINotificationBridge.hook(module, classLoader)
        doOnAppCreated { app ->
            Log.i(TAG, "SystemUI created: ${app.packageName}")
            Directory.initialize(app)
            SystemUIMediaUtils.init(app)
            SystemUIMediaBridge.initialize(app)
            SystemUINotificationBridge.initialize(app)
            NotificationCoverHelper.initialize(app)
            hostCentralIfNeeded(app)
        }
    }

    /**
     * 没装词幕时，由 ZHITool 在 **SystemUI 进程内** 托管 lyricon central：provider 与订阅端都只认
     * SystemUI 的 central（见 subscriber/provider 的 SYSTEM_UI_PACKAGE_NAME），故只装 provider 不装词幕时，
     * 必须有人在 SystemUI 起 central——这里补上。装了词幕则让词幕托管，避免两个 central 抢同一 provider。
     * 整体 runCatching 兜底，绝不让 SystemUI 崩（PackageHooker 的回调本身也已 try/catch）。
     */
    private fun hostCentralIfNeeded(ctx: Context) {
        runCatching {
            if (lyriconHostInstalled(ctx)) {
                Log.i(TAG, "词幕 present -> defer central hosting to 词幕")
            } else {
                BridgeCentral.initialize(ctx)
                BridgeCentral.sendBootCompleted()
                Log.i(TAG, "no 词幕 -> ZHITool hosts lyricon central in SystemUI")
            }
        }.onFailure { Log.w(TAG, "host central in SystemUI failed", it) }
    }

    private fun lyriconHostInstalled(ctx: Context): Boolean =
        LYRICON_HOST_PACKAGES.any { pkg ->
            runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
}
