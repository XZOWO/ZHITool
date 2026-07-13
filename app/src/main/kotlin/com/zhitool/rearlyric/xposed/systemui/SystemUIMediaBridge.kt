/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.xposed.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.core.MediaSessionBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaSession bridge hosted inside SystemUI by LSPosed.
 *
 * SystemUI already has privileged access to active media sessions. It publishes primitive
 * snapshots to the normal ZHITool process and executes transport commands there, so SuperLyric
 * and the rear control panel do not need notification-listener access in the app process.
 */
object SystemUIMediaBridge {
    private const val TAG = "ZhiMediaBridge"
    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hostContext: Context? = null

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaSessionBridge.ACTION_REQUEST -> publishRequested(intent.getStringExtra(MediaSessionBridge.EXTRA_PACKAGE))
                MediaSessionBridge.ACTION_COMMAND -> executeCommand(intent)
            }
        }
    }

    fun initialize(context: Context) {
        hostContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                requestReceiver,
                IntentFilter().apply {
                    addAction(MediaSessionBridge.ACTION_REQUEST)
                    addAction(MediaSessionBridge.ACTION_COMMAND)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "register receiver failed", it) }

        SystemUIMediaUtils.registerListener(object : SystemUIMediaUtils.MediaControllerCallback {
            override fun onMediaChanged(controller: MediaController, metadata: MediaMetadata) = publish(controller)
            override fun onPlaybackStateChanged(controller: MediaController, state: PlaybackState) = publish(controller)
            override fun onSessionDestroyed(controller: MediaController) {
                SystemUIMediaUtils.controllers().firstOrNull { it.packageName == controller.packageName }?.let(::publish)
                    ?: publishUnavailable(controller.packageName)
            }
        })
        // The active-session callback may have fired before this listener was registered.
        SystemUIMediaUtils.controllers().forEach(::publish)
    }

    private fun publishRequested(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            val exact = SystemUIMediaUtils.controllers().firstOrNull { it.packageName == packageName }
            if (exact != null) {
                publish(exact)
            } else {
                publishUnavailable(packageName)
                SystemUIMediaUtils.controllerFor(null)?.let(::publish)
            }
            return
        }
        SystemUIMediaUtils.controllers().forEach(::publish)
    }

    private fun publish(controller: MediaController) {
        val ctx = hostContext ?: return
        val packageName = controller.packageName ?: return
        val metadata = controller.metadata
        val state = controller.playbackState
        val favorite = favoriteAction(state)
        val favoriteName = favorite?.name?.toString()?.lowercase().orEmpty()
        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString()

        runCatching {
            ctx.sendBroadcast(
                Intent(MediaSessionBridge.ACTION_SNAPSHOT)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(MediaSessionBridge.EXTRA_PACKAGE, packageName)
                    .putExtra(MediaSessionBridge.EXTRA_AVAILABLE, true)
                    .putExtra(MediaSessionBridge.EXTRA_TITLE, title)
                    .putExtra(MediaSessionBridge.EXTRA_ARTIST, artist)
                    .putExtra(MediaSessionBridge.EXTRA_DURATION, metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                    .putExtra(MediaSessionBridge.EXTRA_PLAYBACK_STATE, state?.state ?: PlaybackState.STATE_NONE)
                    .putExtra(MediaSessionBridge.EXTRA_POSITION, state?.position ?: 0L)
                    .putExtra(MediaSessionBridge.EXTRA_POSITION_UPDATED_AT, state?.lastPositionUpdateTime ?: 0L)
                    .putExtra(MediaSessionBridge.EXTRA_SPEED, state?.playbackSpeed ?: 0f)
                    .putExtra(MediaSessionBridge.EXTRA_CAN_FAVORITE, favorite != null)
                    .putExtra(MediaSessionBridge.EXTRA_IS_FAVORITED, FAVORITED_KEYS.any(favoriteName::contains))
            )
        }.onFailure { Log.w(TAG, "publish snapshot failed for $packageName", it) }
    }

    private fun publishUnavailable(packageName: String?) {
        val ctx = hostContext ?: return
        if (packageName.isNullOrBlank()) return
        runCatching {
            ctx.sendBroadcast(
                Intent(MediaSessionBridge.ACTION_SNAPSHOT)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(MediaSessionBridge.EXTRA_PACKAGE, packageName)
                    .putExtra(MediaSessionBridge.EXTRA_AVAILABLE, false)
            )
        }.onFailure { Log.w(TAG, "publish unavailable failed for $packageName", it) }
    }

    private fun executeCommand(intent: Intent) {
        val packageName = intent.getStringExtra(MediaSessionBridge.EXTRA_PACKAGE)
        val controller = SystemUIMediaUtils.controllerFor(packageName) ?: return
        runCatching {
            when (intent.getStringExtra(MediaSessionBridge.EXTRA_COMMAND)) {
                MediaSessionBridge.COMMAND_PLAY_PAUSE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                MediaSessionBridge.COMMAND_NEXT -> controller.transportControls.skipToNext()
                MediaSessionBridge.COMMAND_PREVIOUS -> controller.transportControls.skipToPrevious()
                MediaSessionBridge.COMMAND_SEEK_TO -> controller.transportControls.seekTo(
                    intent.getLongExtra(MediaSessionBridge.EXTRA_POSITION, 0L).coerceAtLeast(0L)
                )
                MediaSessionBridge.COMMAND_FAVORITE -> favoriteAction(controller.playbackState)?.let {
                    controller.transportControls.sendCustomAction(it.action, it.extras)
                }
            }
            // Most players callback immediately; this delayed snapshot also covers weak implementations.
            mainHandler.postDelayed({ publish(controller) }, 250L)
        }.onFailure { Log.w(TAG, "execute media command failed for $packageName", it) }
    }

    private fun favoriteAction(state: PlaybackState?): PlaybackState.CustomAction? =
        state?.customActions?.firstOrNull { action ->
            val name = action.name?.toString()?.lowercase().orEmpty()
            FAVORITE_KEYS.any(name::contains)
        }

    private val FAVORITE_KEYS = listOf("收藏", "喜欢", "红心", "favor", "like", "heart", "love")
    private val FAVORITED_KEYS = listOf("已", "取消", "remove", "unfav", "unlike", "favorited", "liked")
}
