/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.systemui

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.tools.notify.NotificationBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures SystemUI's own notification callback and keeps each notification PendingIntent inside
 * the privileged process. ZHITool receives only display text plus an opaque click token.
 */
object SystemUINotificationBridge {
    private const val TAG = "ZhiNotifyBridge"
    private const val LISTENER_CLASS = "com.android.systemui.statusbar.NotificationListener"

    private data class ClickTarget(val pendingIntent: PendingIntent, val token: String)

    private val hookInstalled = AtomicBoolean(false)
    private val receiverInstalled = AtomicBoolean(false)
    private val clickTargets = ConcurrentHashMap<String, ClickTarget>()

    @Volatile
    private var hostContext: Context? = null

    private val clickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NotificationBridge.ACTION_CLICK) return
            val key = intent.getStringExtra(NotificationBridge.EXTRA_KEY) ?: return
            val token = intent.getStringExtra(NotificationBridge.EXTRA_CLICK_TOKEN) ?: return
            val target = clickTargets[key]?.takeIf { it.token == token } ?: return
            val ctx = hostContext ?: return
            runCatching {
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                }
                target.pendingIntent.send(ctx, 0, null, null, null, null, options.toBundle())
            }.onFailure { Log.w(TAG, "execute notification click failed for $key", it) }
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        if (!hookInstalled.compareAndSet(false, true)) return
        runCatching {
            val listenerClass = classLoader.loadClass(LISTENER_CLASS)
            val posted = listenerClass.declaredMethods.firstOrNull { method ->
                method.name == "onNotificationPosted" &&
                    method.parameterTypes.firstOrNull() == StatusBarNotification::class.java
            } ?: error("onNotificationPosted not found")
            val removed = listenerClass.declaredMethods.firstOrNull { method ->
                method.name == "onNotificationRemoved" &&
                    method.parameterTypes.firstOrNull() == StatusBarNotification::class.java
            }

            module.hook(posted).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    runCatching { (chain.getArg(0) as? StatusBarNotification)?.let(::onPosted) }
                        .onFailure { module.log(Log.ERROR, TAG, "notification dispatch failed", it) }
                    return result
                }
            })
            if (removed != null) {
                module.hook(removed).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            (chain.getArg(0) as? StatusBarNotification)?.key?.let(clickTargets::remove)
                        }.onFailure { module.log(Log.WARN, TAG, "notification cleanup failed", it) }
                        return result
                    }
                })
            }
            module.log(Log.INFO, TAG, "SystemUI notification bridge hooked")
        }.onFailure {
            hookInstalled.set(false)
            module.log(Log.ERROR, TAG, "SystemUI notification hook failed", it)
        }
    }

    fun initialize(context: Context) {
        hostContext = context.applicationContext
        if (!receiverInstalled.compareAndSet(false, true)) return
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                clickReceiver,
                IntentFilter(NotificationBridge.ACTION_CLICK),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "register click receiver failed", it) }
    }

    private fun onPosted(sbn: StatusBarNotification) {
        val ctx = hostContext ?: return
        val notification = sbn.notification ?: return
        if (sbn.packageName == BuildConfig.APPLICATION_ID) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val pendingIntent = notification.contentIntent ?: notification.fullScreenIntent
        val target = if (pendingIntent != null) {
            clickTargets.compute(sbn.key) { _, existing ->
                ClickTarget(pendingIntent, existing?.token ?: UUID.randomUUID().toString())
            }
        } else {
            clickTargets.remove(sbn.key)
            null
        }
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        runCatching {
            ctx.sendBroadcast(
                Intent(NotificationBridge.ACTION_POSTED)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(NotificationBridge.EXTRA_KEY, sbn.key)
                    .putExtra(NotificationBridge.EXTRA_PACKAGE, sbn.packageName)
                    .putExtra(NotificationBridge.EXTRA_TITLE, title)
                    .putExtra(NotificationBridge.EXTRA_TEXT, text)
                    .putExtra(NotificationBridge.EXTRA_OPENABLE, target != null)
                    .putExtra(NotificationBridge.EXTRA_CLICK_TOKEN, target?.token)
            )
        }.onFailure { Log.w(TAG, "send notification snapshot failed for ${sbn.key}", it) }
    }
}
