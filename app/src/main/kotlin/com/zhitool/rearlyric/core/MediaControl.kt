/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.zhitool.rearlyric.tools.notify.ZhiNotificationListener
import kotlin.concurrent.thread

/**
 * 通过系统媒体会话（MediaSession）控制当前播放器：切歌 / 播放暂停 / 收藏。
 *
 * 走 [MediaSessionManager.getActiveSessions]（需通知使用权——本应用已为背屏通知申请，
 * 复用同一个 [ZhiNotificationListener] 组件），拿到当前播放器的 [MediaController] 后用
 * transportControls 控制；收藏走自定义动作（custom action，按动作显示名匹配「收藏 / 喜欢」）。
 *
 * 实测 QQ 音乐：rating type=NONE，收藏即为 custom action，名称在「收藏」↔「已收藏」之间切换，
 * 据此还能读出当前是否已收藏；标准动作 actions 含 SKIP_NEXT/SKIP_PREV/PLAY_PAUSE/SEEK_TO。
 * 部分音乐 app 适配不全（没有收藏 custom action），此时 [State.canFavorite]=false，UI 置灰收藏键，
 * 但切歌/暂停仍正常。
 *
 * 没有通知使用权（拿不到 controller）时，切歌/暂停降级用 root 媒体键；收藏无法降级。
 */
object MediaControl {

    data class State(
        /** 是否拿到了当前播放器的会话（能精确控制 + 读状态）。 */
        val available: Boolean = false,
        val isPlaying: Boolean = false,
        /** 该播放器是否暴露了收藏动作（适配不全的 app 为 false）。 */
        val canFavorite: Boolean = false,
        /** 当前是否已收藏（best-effort，按动作名含「已 / 取消」等判断）。 */
        val isFavorited: Boolean = false,
    )

    /** 收藏动作的显示名关键词（命中其一即认为是收藏键）。 */
    private val FAVORITE_KEYS = listOf("收藏", "喜欢", "红心", "favor", "like", "heart", "love")

    /** 「已收藏」状态关键词（命中说明当前已收藏，点一下会取消）。 */
    private val FAVORITED_KEYS = listOf("已", "取消", "remove", "unfav", "unlike", "favorited", "liked")

    // KeyEvent 媒体键码（root 降级用）。
    private const val KEY_PLAY_PAUSE = 85
    private const val KEY_NEXT = 87
    private const val KEY_PREVIOUS = 88

    private fun manager(context: Context): MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    /** 取目标播放器的 controller；找不到精确包名则退而取第一个活跃会话。 */
    private fun controllerFor(context: Context, pkg: String?): MediaController? {
        val msm = manager(context) ?: return null
        val comp = ComponentName(context, ZhiNotificationListener::class.java)
        // 未授通知使用权时 getActiveSessions 抛 SecurityException，吞掉走降级。
        val list = runCatching { msm.getActiveSessions(comp) }.getOrNull() ?: return null
        if (list.isEmpty()) return null
        return list.firstOrNull { it.packageName == pkg } ?: list.first()
    }

    private fun favoriteAction(state: PlaybackState?): PlaybackState.CustomAction? {
        val actions = state?.customActions ?: return null
        return actions.firstOrNull { ca ->
            val n = ca.name?.toString()?.lowercase().orEmpty()
            FAVORITE_KEYS.any { n.contains(it) }
        }
    }

    /** 读取当前播放器的控制能力与状态（建议在后台线程调用，含一次 binder 查询）。 */
    fun readState(context: Context, pkg: String?): State {
        val c = controllerFor(context, pkg) ?: return State()
        val ps = c.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val fav = favoriteAction(ps)
        val favorited = if (fav != null) {
            val n = fav.name?.toString()?.lowercase().orEmpty()
            FAVORITED_KEYS.any { n.contains(it) }
        } else {
            false
        }
        return State(available = true, isPlaying = playing, canFavorite = fav != null, isFavorited = favorited)
    }

    fun playPause(context: Context, pkg: String?) {
        val c = controllerFor(context, pkg)
        if (c != null) {
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) c.transportControls.pause() else c.transportControls.play()
        } else {
            dispatchKey(KEY_PLAY_PAUSE)
        }
    }

    fun next(context: Context, pkg: String?) {
        val c = controllerFor(context, pkg)
        if (c != null) c.transportControls.skipToNext() else dispatchKey(KEY_NEXT)
    }

    fun previous(context: Context, pkg: String?) {
        val c = controllerFor(context, pkg)
        if (c != null) c.transportControls.skipToPrevious() else dispatchKey(KEY_PREVIOUS)
    }

    /** 当前播放进度 + 播放态（自系统会话直读，独立于词幕）。 */
    data class Playback(val positionMs: Long, val playing: Boolean)

    /**
     * 从系统媒体会话直读「当前播放进度（外推到此刻）+ 播放态」——这样进度由我们自己掌握，
     * 不依赖词幕持续上报；词幕断了/不提供进度时歌词仍能跟着实时跑。
     * 返回 null = 拿不到会话（无通知使用权 / 无活跃会话 / state=NONE / 进度无效）。
     */
    fun readPlayback(context: Context, pkg: String?): Playback? {
        val c = controllerFor(context, pkg) ?: return null
        val ps = c.playbackState ?: return null
        if (ps.state == PlaybackState.STATE_NONE || ps.position < 0L) return null
        val playing = ps.state == PlaybackState.STATE_PLAYING
        val updated = ps.lastPositionUpdateTime
        val pos = if (playing && updated > 0L) {
            // PlaybackState 设计即如此外推：当前进度 = 上次设值进度 + (现在 - 上次设值时刻) * 速率。
            val speed = ps.playbackSpeed.takeIf { it != 0f } ?: 1f
            ps.position + ((SystemClock.elapsedRealtime() - updated) * speed).toLong()
        } else {
            ps.position
        }
        return Playback(pos.coerceAtLeast(0L), playing)
    }

    /** 会话元数据：歌名/歌手/封面字节（SuperLyric 没给元数据时从系统会话补）。 */
    data class Meta(val title: String?, val artist: String?, val coverBytes: ByteArray?)

    fun readMetadata(context: Context, pkg: String?, withCover: Boolean = false): Meta? {
        val c = controllerFor(context, pkg) ?: return null
        val m = c.metadata ?: return null
        val title = m.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val artist = (m.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString())?.takeIf { it.isNotBlank() }
        val bytes = if (withCover) {
            val art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            art?.let { bmp ->
                runCatching {
                    val s = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, s)
                    s.toByteArray()
                }.getOrNull()
            }
        } else {
            null
        }
        return if (title == null && artist == null && bytes == null) null else Meta(title, artist, bytes)
    }

    /** 歌曲总时长（毫秒），从会话元数据取；取不到返回 0。 */
    fun readDuration(context: Context, pkg: String?): Long {
        val c = controllerFor(context, pkg) ?: return 0L
        val d = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return d.coerceAtLeast(0L)
    }

    /** 拖动调整播放进度（毫秒）。需会话支持 ACTION_SEEK_TO（QQ 实测支持）。 */
    fun seekTo(context: Context, pkg: String?, positionMs: Long) {
        controllerFor(context, pkg)?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 收藏（切换）：只能走 custom action，无 root 降级。返回是否成功下发。 */
    fun toggleFavorite(context: Context, pkg: String?): Boolean {
        val c = controllerFor(context, pkg) ?: return false
        val fav = favoriteAction(c.playbackState) ?: return false
        c.transportControls.sendCustomAction(fav.action, fav.extras)
        return true
    }

    /** root 媒体键降级（无通知使用权时的切歌/暂停）。 */
    private fun dispatchKey(keyCode: Int) {
        if (!RootShell.available) return
        thread(name = "zhi-media-key") { RootShell.run("input keyevent $keyCode") }
    }
}
