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
 * - **歌名 / 歌手 / 封面 / 播放进度**：由 LSPosed 在 SystemUI 内读取**系统会话（MediaSession）**并
 *   桥接给应用——无需通知使用权，随切歌可靠变化，且拖动 seek 后进度条会实时更新。
 * - **当前句歌词文本 + 逐字时间**：由 SuperLyric `onLyric` 提供（按**绝对时间**，与会话进度同一时间轴，
 *   故逐字扫过能跟系统进度对齐）。
 *
 * 鲁棒性：锁定单一发布者（避免上一首/另一来源交替）；会话 id 仅在完整歌曲身份（歌名+歌手）变化时换。
 */
object SuperLyricSource {
    private const val TAG = "ZhiSuperLyric"
    private const val PUBLISHER_STALE_MS = 4000L

    @Volatile
    private var receiver: ISuperLyricReceiver.Stub? = null

    private val stateLock = Any()
    private val metaNormalizer = SuperLyricMetaNormalizer()
    private var currentLine: RichLyricLine? = null
    private var title: String? = null
    private var artist: String? = null
    private var songKey: String? = null
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
        synchronized(stateLock) {
            metaNormalizer.reset()
            currentLine = null
            title = null
            artist = null
            songKey = null
            sessionId = 0L
            activePublisher = null
            lastLineAt = 0L
        }
    }

    /**
     * 接收系统 MediaSession 的原始元数据并立刻归一化、提交。
     *
     * 小米适配播放器在歌词开始后会把 TITLE 改成当前句，并把 ARTIST 改成「歌名-歌手」。
     * 归一化器会结合当前 SuperLyric 行和切歌词前已建立的歌名/歌手做状态化判断，兼容无空格及
     * Unicode 横线；歌曲身份使用完整歌名+歌手，而不是逐句变化的 TITLE。
     */
    internal fun onMediaMeta(
        rawTitle: String?,
        rawArtist: String?,
    ): SuperLyricMetaNormalizer.Result? = synchronized(stateLock) {
        if (receiver == null) return@synchronized null
        val normalized = metaNormalizer.resolve(rawTitle, rawArtist, currentLine?.text)
        applyNormalizedMeta(normalized)
        normalized
    }

    /** 调用方已在 [stateLock] 内；返回是否真的换了歌曲身份。 */
    private fun applyNormalizedMeta(meta: SuperLyricMetaNormalizer.Result): Boolean {
        val nextTitle = meta.displayTitle?.takeIf { it.isNotBlank() }
        val nextArtist = meta.displayArtist?.takeIf { it.isNotBlank() }
        val nextKey = meta.songKey
        val realSongChange = songKey != null && nextKey != null && songKey != nextKey
        var changed = realSongChange

        if (realSongChange) {
            sessionId++
            // 若新的 MediaSession TITLE 已等于刚收到的 SuperLyric 行，这一行属于新歌，不能清掉。
            if (!meta.titleIsLyric) currentLine = null
        }
        if (nextTitle != null && nextTitle != title) {
            title = nextTitle
            changed = true
        }
        if (nextArtist != null && nextArtist != artist) {
            artist = nextArtist
            changed = true
        }
        if (nextKey != null) songKey = nextKey
        if (changed) rebuild()
        return realSongChange
    }

    private fun handleLyric(publisher: String?, data: SuperLyricData) {
        synchronized(stateLock) {
            val now = SystemClock.elapsedRealtime()
            // 锁定单一发布者：活跃发布者新鲜时忽略其它发布者的行（避免上一首/另一来源交替）。
            if (publisher != activePublisher) {
                if (activePublisher == null || now - lastLineAt > PUBLISHER_STALE_MS) {
                    activePublisher = publisher
                } else {
                    return@synchronized
                }
            }
            lastLineAt = now

            if (!publisher.isNullOrBlank()) LyricBus.playerPackage.value = publisher
            if (data.hasBase64Icon()) {
                runCatching { Base64.decode(data.base64Icon, Base64.DEFAULT) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { LyricBus.setCover(it) }
            }
            // 会话尚未可读时，用 SuperLyric 自带歌名/歌手建立同一套身份锚；后续仍由会话精化。
            if (data.hasTitle() || data.hasArtist()) {
                val hinted = metaNormalizer.resolve(
                    rawTitle = data.title?.takeIf { data.hasTitle() },
                    rawArtist = data.artist?.takeIf { data.hasArtist() },
                    currentLyric = currentLine?.text,
                )
                applyNormalizedMeta(hinted)
            }

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
    }

    /** 稳定 id：同一首歌期间 id 不变，FullLyricView 不会逐句误判换歌。 */
    private fun rebuild() {
        val lines = currentLine?.let { listOf(it) } ?: emptyList()
        LyricBus.song.value = Song(id = "sl:$sessionId", name = title, artist = artist, lyrics = lines)
    }
}
