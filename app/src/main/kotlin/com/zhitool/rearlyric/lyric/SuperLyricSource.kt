/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.lyric

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/**
 * SuperLyric 歌词源（与词幕生态并行的可选源）。
 *
 * SuperLyric 是**实时逐句广播**（`onLyric` 每来一句当前句 + 逐字），只能拿到"当前一句"。
 * 故采用「单句 + 大封面」展示（见 [com.zhitool.rearlyric.rear.FullLyricView] 的 singleLineMode）：
 * 只保留**当前一句**（不累积、不滚动）。
 *
 * 分工（关键，修"歌名卡住/进度条不动"）：
 * - **歌名 / 歌手 / 封面 / 播放进度**：一律由**系统会话（MediaSession）**权威提供——随切歌可靠变化，
 *   进度真实（拖动 seek 后进度条会动）。`LyricService` 周期把会话元数据喂给 [onMeta]、进度喂给 LyricBus。
 * - **当前句歌词文本 + 逐字时间**：由 SuperLyric `onLyric` 提供（按**绝对时间**，与会话进度同一时间轴，
 *   故逐字扫过能跟系统进度对齐）。
 *
 * 鲁棒性：锁定单一发布者（避免上一首/另一来源交替）；会话 id 仅在歌名真变时换（=换歌→清当前句 + 播切换动画）。
 */
object SuperLyricSource {
    private const val TAG = "ZhiSuperLyric"
    private const val PUBLISHER_STALE_MS = 4000L

    /** 出歌词后歌手区「歌名 - 歌手」的分隔符（QQ音乐/网易云等）。 */
    private const val META_ARTIST_SEP = " - "

    @Volatile
    private var receiver: ISuperLyricReceiver.Stub? = null

    private var currentLine: RichLyricLine? = null
    private var title: String? = null
    private var artist: String? = null
    private var sessionId = 0L
    private var activePublisher: String? = null
    private var lastLineAt = 0L

    fun start() {
        if (receiver != null) return
        runCatching {
            if (!SuperLyricHelper.isAvailable()) {
                Log.w(TAG, "SuperLyric service not available (module not installed/active) — will still try register")
            }
        }
        val r = object : ISuperLyricReceiver.Stub() {
            override fun onLyric(publisher: String?, data: SuperLyricData?) {
                runCatching { if (data != null) handleLyric(publisher, data) }
                    .onFailure { Log.w(TAG, "onLyric handle failed", it) }
            }

            override fun onStop(publisher: String?, data: SuperLyricData?) {
                if (publisher == null || publisher == activePublisher) LyricBus.playing.value = false
            }
        }
        val ok = runCatching { SuperLyricHelper.registerReceiver(r); true }.getOrDefault(false)
        if (ok) {
            receiver = r
            resetAll()
            Log.i(TAG, "SuperLyric receiver registered")
        } else {
            Log.e(TAG, "SuperLyric registerReceiver failed")
        }
    }

    fun stop() {
        receiver?.let { rec -> runCatching { SuperLyricHelper.unregisterReceiver(rec) } }
        receiver = null
        resetAll()
    }

    private fun resetAll() {
        currentLine = null
        title = null
        artist = null
        sessionId = 0L
        activePublisher = null
        lastLineAt = 0L
    }

    /**
     * 把系统会话元数据解析成真正的「歌名 / 歌手」。
     *
     * QQ音乐 / 网易云 等播放器**出歌词后**会把当前句歌词塞进会话 TITLE、并把歌手区改成
     * 「歌名 - 歌手」。若直接拿 TITLE 当歌名，会因每句歌词刷新 TITLE 被一直误判成切歌、歌词出不来。
     * 故：
     * - 歌手区含「 - 」（出歌词态）：从歌手区拆「歌名 - 歌手」，TITLE（当前句歌词）忽略；
     * - 否则（刚切歌/未出歌词）：TITLE=歌名、ARTIST=歌手。
     *
     * 这样同一首歌内歌名稳定，不再随逐句刷新；返回 (歌名, 歌手)。
     */
    fun resolveMeta(rawTitle: String?, rawArtist: String?): Pair<String?, String?> {
        val a = rawArtist?.trim()
        if (!a.isNullOrEmpty() && a.contains(META_ARTIST_SEP)) {
            val name = a.substringBeforeLast(META_ARTIST_SEP).trim()
            val singer = a.substringAfterLast(META_ARTIST_SEP).trim()
            if (name.isNotEmpty() && singer.isNotEmpty()) return name to singer
        }
        return rawTitle to rawArtist
    }

    /**
     * 系统会话元数据（[LyricService] 周期调用，权威，已 [resolveMeta] 归一化）：
     * 歌名变=换歌 → session++ 清当前句（播切换动画）。
     * 仅"从一首换到另一首"（前后歌名都非空且不同）才算换歌；初次 null→歌名 不清当前句。
     */
    fun onMeta(metaTitle: String?, metaArtist: String?) {
        if (receiver == null) return
        val t = metaTitle?.takeIf { it.isNotBlank() }
        val a = metaArtist?.takeIf { it.isNotBlank() }
        var changed = false
        if (t != title) {
            val realSongChange = title != null && t != null
            title = t
            if (realSongChange) {
                sessionId++
                currentLine = null
            }
            changed = true
        }
        if (a != artist) {
            artist = a
            changed = true
        }
        if (changed) rebuild()
    }

    private fun handleLyric(publisher: String?, data: SuperLyricData) {
        val now = SystemClock.elapsedRealtime()
        // 锁定单一发布者：活跃发布者新鲜时忽略其它发布者的行（避免上一首/另一来源交替）。
        if (publisher != activePublisher) {
            if (activePublisher == null || now - lastLineAt > PUBLISHER_STALE_MS) {
                activePublisher = publisher
            } else {
                return
            }
        }
        lastLineAt = now

        if (!publisher.isNullOrBlank()) LyricBus.playerPackage.value = publisher
        if (data.hasBase64Icon()) {
            runCatching { Base64.decode(data.base64Icon, Base64.DEFAULT) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { LyricBus.setCover(it) }
        }
        // 会话还没给歌名/歌手时，先用 SuperLyric 自带的兜底（之后会话元数据为准）。
        if (title == null && data.hasTitle()) data.title?.takeIf { it.isNotBlank() }?.let { title = it }
        if (artist == null && data.hasArtist()) data.artist?.takeIf { it.isNotBlank() }?.let { artist = it }

        if (data.hasLyric()) {
            val line = data.lyric
            val text = line?.text
            if (line != null && !text.isNullOrBlank()) {
                // 绝对时间：与系统会话进度同一时间轴，逐字扫过随系统进度走。
                val words = line.words?.takeIf { it.isNotEmpty() }?.mapNotNull { w ->
                    val wt = w.word ?: return@mapNotNull null
                    LyricWord(begin = w.startTime, end = w.endTime.coerceAtLeast(w.startTime), text = wt)
                }
                currentLine = RichLyricLine(
                    begin = line.startTime,
                    end = line.endTime.coerceAtLeast(line.startTime + 300L),
                    text = text,
                    words = words,
                    secondary = if (data.hasSecondary()) data.secondary?.text else null,
                    translation = if (data.hasTranslation()) data.translation?.text else null,
                )
                rebuild()
            }
        }
    }

    /** 稳定 id：同一首歌期间 id 不变，FullLyricView 不会逐句误判换歌。 */
    private fun rebuild() {
        val lines = currentLine?.let { listOf(it) } ?: emptyList()
        LyricBus.song.value = Song(id = "sl:$sessionId", name = title, artist = artist, lyrics = lines)
    }
}
