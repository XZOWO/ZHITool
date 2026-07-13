/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * MediaSession state and controls routed through ZHITool's LSPosed hook in SystemUI.
 *
 * SystemUI owns the privileged MediaController. The normal app process receives primitive state
 * snapshots and sends primitive commands, so SuperLyric and the rear control panel do not require
 * notification-listener access. That permission remains independent and is used only by the rear
 * notification-forwarding tool.
 */
object MediaControl {

    data class State(
        val available: Boolean = false,
        val isPlaying: Boolean = false,
        val canFavorite: Boolean = false,
        val isFavorited: Boolean = false,
    )

    data class Playback(val positionMs: Long, val playing: Boolean)
    data class Meta(val title: String?, val artist: String?)

    private data class Snapshot(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val durationMs: Long,
        val playbackState: Int,
        val positionMs: Long,
        val positionUpdatedAt: Long,
        val speed: Float,
        val canFavorite: Boolean,
        val isFavorited: Boolean,
        val receivedAt: Long,
    )

    private val initialized = AtomicBoolean(false)
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val lastRequests = ConcurrentHashMap<String, Long>()

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MediaSessionBridge.ACTION_SNAPSHOT) return
            val packageName = intent.getStringExtra(MediaSessionBridge.EXTRA_PACKAGE) ?: return
            if (!intent.getBooleanExtra(MediaSessionBridge.EXTRA_AVAILABLE, false)) {
                snapshots.remove(packageName)
                return
            }
            snapshots[packageName] = Snapshot(
                packageName = packageName,
                title = intent.getStringExtra(MediaSessionBridge.EXTRA_TITLE),
                artist = intent.getStringExtra(MediaSessionBridge.EXTRA_ARTIST),
                durationMs = intent.getLongExtra(MediaSessionBridge.EXTRA_DURATION, 0L).coerceAtLeast(0L),
                playbackState = intent.getIntExtra(MediaSessionBridge.EXTRA_PLAYBACK_STATE, PlaybackState.STATE_NONE),
                positionMs = intent.getLongExtra(MediaSessionBridge.EXTRA_POSITION, 0L).coerceAtLeast(0L),
                positionUpdatedAt = intent.getLongExtra(MediaSessionBridge.EXTRA_POSITION_UPDATED_AT, 0L),
                speed = intent.getFloatExtra(MediaSessionBridge.EXTRA_SPEED, 0f),
                canFavorite = intent.getBooleanExtra(MediaSessionBridge.EXTRA_CAN_FAVORITE, false),
                isFavorited = intent.getBooleanExtra(MediaSessionBridge.EXTRA_IS_FAVORITED, false),
                receivedAt = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        ContextCompat.registerReceiver(
            context.applicationContext,
            snapshotReceiver,
            IntentFilter(MediaSessionBridge.ACTION_SNAPSHOT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        requestSnapshot(context, null, force = true)
    }

    fun readState(context: Context, pkg: String?): State {
        val snapshot = snapshotFor(context, pkg) ?: return State()
        return State(
            available = true,
            isPlaying = snapshot.playbackState == PlaybackState.STATE_PLAYING,
            canFavorite = snapshot.canFavorite,
            isFavorited = snapshot.isFavorited,
        )
    }

    fun readPlayback(context: Context, pkg: String?): Playback? {
        val snapshot = snapshotFor(context, pkg) ?: return null
        if (snapshot.playbackState == PlaybackState.STATE_NONE) return null
        val playing = snapshot.playbackState == PlaybackState.STATE_PLAYING
        val position = if (playing && snapshot.positionUpdatedAt > 0L) {
            val speed = snapshot.speed.takeIf { it != 0f } ?: 1f
            snapshot.positionMs + ((SystemClock.elapsedRealtime() - snapshot.positionUpdatedAt) * speed).toLong()
        } else {
            snapshot.positionMs
        }
        return Playback(position.coerceAtLeast(0L), playing)
    }

    fun readMetadata(context: Context, pkg: String?): Meta? {
        val snapshot = snapshotFor(context, pkg) ?: return null
        return if (snapshot.title == null && snapshot.artist == null) null else Meta(snapshot.title, snapshot.artist)
    }

    fun readDuration(context: Context, pkg: String?): Long =
        snapshotFor(context, pkg)?.durationMs ?: 0L

    fun playPause(context: Context, pkg: String?) {
        if (!sendCommand(context, pkg, MediaSessionBridge.COMMAND_PLAY_PAUSE)) dispatchKey(KEY_PLAY_PAUSE)
    }

    fun next(context: Context, pkg: String?) {
        if (!sendCommand(context, pkg, MediaSessionBridge.COMMAND_NEXT)) dispatchKey(KEY_NEXT)
    }

    fun previous(context: Context, pkg: String?) {
        if (!sendCommand(context, pkg, MediaSessionBridge.COMMAND_PREVIOUS)) dispatchKey(KEY_PREVIOUS)
    }

    fun seekTo(context: Context, pkg: String?, positionMs: Long) {
        sendCommand(context, pkg, MediaSessionBridge.COMMAND_SEEK_TO, positionMs.coerceAtLeast(0L))
    }

    fun toggleFavorite(context: Context, pkg: String?): Boolean {
        val snapshot = snapshotFor(context, pkg) ?: return false
        if (!snapshot.canFavorite) return false
        return sendCommand(context, pkg, MediaSessionBridge.COMMAND_FAVORITE)
    }

    private fun snapshotFor(context: Context, pkg: String?): Snapshot? {
        initialize(context)
        val exact = pkg?.let(snapshots::get)
        val selected = exact
            ?: snapshots.values.firstOrNull { it.playbackState == PlaybackState.STATE_PLAYING }
            ?: snapshots.values.maxByOrNull { it.receivedAt }
        if ((pkg != null && exact == null) ||
            selected == null ||
            SystemClock.elapsedRealtime() - selected.receivedAt > SNAPSHOT_REFRESH_MS
        ) {
            requestSnapshot(context, pkg, force = false)
        }
        return selected
    }

    private fun requestSnapshot(context: Context, pkg: String?, force: Boolean) {
        val key = pkg.orEmpty()
        val now = SystemClock.elapsedRealtime()
        if (!force && now - (lastRequests[key] ?: 0L) < REQUEST_THROTTLE_MS) return
        lastRequests[key] = now
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(MediaSessionBridge.ACTION_REQUEST)
                    .setPackage(MediaSessionBridge.SYSTEM_UI_PACKAGE)
                    .putExtra(MediaSessionBridge.EXTRA_PACKAGE, pkg)
            )
        }
    }

    private fun sendCommand(context: Context, pkg: String?, command: String, positionMs: Long = 0L): Boolean {
        initialize(context)
        val available = snapshotFor(context, pkg) != null
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(MediaSessionBridge.ACTION_COMMAND)
                    .setPackage(MediaSessionBridge.SYSTEM_UI_PACKAGE)
                    .putExtra(MediaSessionBridge.EXTRA_PACKAGE, pkg)
                    .putExtra(MediaSessionBridge.EXTRA_COMMAND, command)
                    .putExtra(MediaSessionBridge.EXTRA_POSITION, positionMs)
            )
        }.onFailure { return false }
        return available
    }

    private fun dispatchKey(keyCode: Int) {
        if (!RootShell.available) return
        thread(name = "zhi-media-key") { RootShell.run("input keyevent $keyCode") }
    }

    private const val KEY_PLAY_PAUSE = 85
    private const val KEY_NEXT = 87
    private const val KEY_PREVIOUS = 88
    private const val SNAPSHOT_REFRESH_MS = 10_000L
    private const val REQUEST_THROTTLE_MS = 5_000L
}
