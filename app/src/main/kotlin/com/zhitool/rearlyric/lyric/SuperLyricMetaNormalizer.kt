/*
 * This file is part of ZHITool - licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.lyric

import java.text.Normalizer

/**
 * Stabilizes the MediaSession metadata shape used by Xiaomi-adapted players.
 *
 * Before lyrics start, those players normally publish `TITLE=song` and `ARTIST=artist`.
 * While lyrics are active, they may instead publish `TITLE=current lyric` and
 * `ARTIST=song-artist`.  The latter must not be treated as a new song on every line.
 *
 * This class deliberately keeps the display fields and [Result.songKey] separate: callers can
 * render the original song/artist text while using the normalized pair as stable song identity.
 * It is stateful because an ordinary artist containing a dash is indistinguishable from the
 * composite form without either the current lyric or a previously established song pair.
 */
internal class SuperLyricMetaNormalizer {

    data class Result(
        val displayTitle: String?,
        val displayArtist: String?,
        val songKey: String?,
        val titleIsLyric: Boolean,
    )

    private data class SongPair(
        val title: String,
        val artist: String,
    ) {
        val key: String = buildSongKey(title, artist)
    }

    private data class SplitCandidate(
        val pair: SongPair,
        val separatorIndex: Int,
        val separatorHasSpace: Boolean,
    )

    private var stablePair: SongPair? = null
    private var pendingPair: SongPair? = null
    private var lastResult = Result(
        displayTitle = null,
        displayArtist = null,
        songKey = null,
        titleIsLyric = false,
    )

    val current: Result
        get() = lastResult

    /** Clears all anchors, for example when the SuperLyric source is stopped. */
    fun reset() {
        stablePair = null
        pendingPair = null
        lastResult = Result(null, null, null, false)
    }

    /**
     * Resolves one MediaSession snapshot.
     *
     * Blank or one-sided snapshots never clear an established pair. A complete snapshot that
     * changes only one side of the previous identity is accepted after two identical observations;
     * this filters the common `new title + old artist` (or the reverse) transition. A lyric-anchored
     * composite is authoritative and is accepted immediately.
     */
    fun resolve(
        rawTitle: String?,
        rawArtist: String?,
        currentLyric: String?,
    ): Result {
        val title = clean(rawTitle)
        val artist = clean(rawArtist)
        val lyric = clean(currentLyric)

        if (title == null && artist == null) return lastResult

        val oldPair = stablePair
        val titleMatchesLyric = title != null && lyric != null && equivalent(title, lyric)
        val splitCandidates = artist?.let(::splitCandidates).orEmpty()

        // Strongest anchor: every part of ARTIST matches the already established pair. This also
        // covers the short race where MediaSession advances TITLE before SuperLyric delivers it.
        if (oldPair != null) {
            val anchored = splitCandidates.firstOrNull {
                equivalent(it.pair.title, oldPair.title) &&
                    equivalent(it.pair.artist, oldPair.artist)
            }
            if (anchored != null) {
                pendingPair = null
                val isLyric = when {
                    title == null -> lastResult.titleIsLyric
                    titleMatchesLyric -> true
                    !equivalent(title, oldPair.title) -> true
                    else -> false
                }
                return publish(oldPair, isLyric)
            }
        }

        if (titleMatchesLyric) {
            // Generic dash splitting is only allowed with this explicit lyric marker. Thus a normal
            // pre-lyric artist such as "AC-DC" remains an artist rather than becoming song + artist.
            val composite = chooseComposite(splitCandidates, oldPair)
            if (composite != null) {
                val accepted = accept(composite, trusted = true)
                return publish(accepted, titleIsLyric = true)
            }

            // TITLE is known to be a lyric, but ARTIST is still blank/plain during a half update.
            // Keep the prior display identity instead of exposing the lyric as a song title.
            if (oldPair != null) return publish(oldPair, titleIsLyric = true)
            return publishBootstrap(
                title = lastResult.displayTitle,
                artist = artist ?: lastResult.displayArtist,
                titleIsLyric = true,
            )
        }

        // A partial snapshot may be a field-by-field MediaSession update. It can fill the initial
        // display, but it cannot replace a stable identity or manufacture a song key.
        if (title == null || artist == null) {
            if (oldPair != null) {
                return publish(
                    oldPair,
                    titleIsLyric = if (title == null) lastResult.titleIsLyric else false,
                )
            }
            return publishBootstrap(
                title = title ?: lastResult.displayTitle,
                artist = artist ?: lastResult.displayArtist,
                titleIsLyric = false,
            )
        }

        // Normal (pre-lyric) metadata. Do not inspect dashes in ARTIST here.
        val accepted = accept(SongPair(title, artist), trusted = false)
        return publish(accepted, titleIsLyric = false)
    }

    private fun accept(candidate: SongPair, trusted: Boolean): SongPair {
        val old = stablePair
        if (old == null) return commit(candidate)

        if (candidate.key == old.key) {
            pendingPair = null
            return old
        }

        if (trusted) return commit(candidate)

        val sameTitle = equivalent(candidate.title, old.title)
        val sameArtist = equivalent(candidate.artist, old.artist)
        if (sameTitle.xor(sameArtist)) {
            // A real same-title/different-artist or same-artist/different-title switch is still
            // accepted, but only after a second consistent full snapshot.
            if (pendingPair?.key == candidate.key) return commit(candidate)
            pendingPair = candidate
            return old
        }

        return commit(candidate)
    }

    private fun commit(pair: SongPair): SongPair {
        stablePair = pair
        pendingPair = null
        return pair
    }

    private fun publish(pair: SongPair, titleIsLyric: Boolean): Result {
        return Result(
            displayTitle = pair.title,
            displayArtist = pair.artist,
            songKey = pair.key,
            titleIsLyric = titleIsLyric,
        ).also { lastResult = it }
    }

    private fun publishBootstrap(
        title: String?,
        artist: String?,
        titleIsLyric: Boolean,
    ): Result {
        return Result(
            displayTitle = title,
            displayArtist = artist,
            songKey = null,
            titleIsLyric = titleIsLyric,
        ).also { lastResult = it }
    }

    private fun chooseComposite(
        candidates: List<SplitCandidate>,
        anchor: SongPair?,
    ): SongPair? {
        if (candidates.isEmpty()) return null

        if (anchor != null) {
            candidates.firstOrNull { equivalent(it.pair.title, anchor.title) }
                ?.let { return it.pair }
            candidates.firstOrNull { equivalent(it.pair.artist, anchor.artist) }
                ?.let { return it.pair }
        }

        // A separator surrounded by spaces is more likely to be the song/artist boundary than an
        // internal dash. Without spaces, prefer the last dash so dashed song titles remain intact.
        return candidates.lastOrNull { it.separatorHasSpace }?.pair
            ?: candidates.maxBy { it.separatorIndex }.pair
    }

    private fun splitCandidates(value: String): List<SplitCandidate> {
        return value.indices.mapNotNull { index ->
            if (!isDash(value[index])) return@mapNotNull null
            val left = clean(value.substring(0, index)) ?: return@mapNotNull null
            val right = clean(value.substring(index + 1)) ?: return@mapNotNull null
            SplitCandidate(
                pair = SongPair(left, right),
                separatorIndex = index,
                separatorHasSpace =
                    value.getOrNull(index - 1)?.isMetadataWhitespace() == true ||
                        value.getOrNull(index + 1)?.isMetadataWhitespace() == true,
            )
        }
    }

    private companion object {
        private val DASHES = setOf(
            '-', '\u058A', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015',
            '\u2212', '\u2E3A', '\u2E3B', '\uFE58', '\uFE63', '\uFF0D',
        )

        private fun clean(value: String?): String? {
            return value
                ?.replace("\u200B", "")
                ?.replace("\uFEFF", "")
                ?.trim { it.isMetadataWhitespace() }
                ?.takeIf { it.isNotEmpty() }
        }

        private fun equivalent(left: String, right: String): Boolean =
            canonical(left) == canonical(right)

        private fun canonical(value: String): String = buildString(value.length) {
            Normalizer.normalize(value, Normalizer.Form.NFKC).forEach { char ->
                when {
                    char.isMetadataWhitespace() || char == '\u200B' || char == '\uFEFF' -> Unit
                    isDash(char) -> append('-')
                    else -> append(char)
                }
            }
        }

        private fun buildSongKey(title: String, artist: String): String {
            val titleKey = canonical(title)
            val artistKey = canonical(artist)
            return "${titleKey.length}:$titleKey|${artistKey.length}:$artistKey"
        }

        private fun isDash(char: Char): Boolean = char in DASHES

        private fun Char.isMetadataWhitespace(): Boolean = isWhitespace() || this == '\u00A0'
    }
}
