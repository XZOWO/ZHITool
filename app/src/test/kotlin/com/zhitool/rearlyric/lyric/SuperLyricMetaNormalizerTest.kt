package com.zhitool.rearlyric.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperLyricMetaNormalizerTest {

    @Test
    fun `pre lyric metadata is kept verbatim and receives a pair identity`() {
        val result = SuperLyricMetaNormalizer().resolve(
            rawTitle = "晴天",
            rawArtist = "周杰伦",
            currentLyric = null,
        )

        assertEquals("晴天", result.displayTitle)
        assertEquals("周杰伦", result.displayArtist)
        assertNotNull(result.songKey)
        assertFalse(result.titleIsLyric)
    }

    @Test
    fun `ascii composite without spaces does not replace title with lyric`() {
        val normalizer = SuperLyricMetaNormalizer()
        val before = normalizer.resolve("晴天", "周杰伦", null)

        val during = normalizer.resolve("故事的小黄花", "晴天-周杰伦", "故事的小黄花")

        assertEquals("晴天", during.displayTitle)
        assertEquals("周杰伦", during.displayArtist)
        assertEquals(before.songKey, during.songKey)
        assertTrue(during.titleIsLyric)
    }

    @Test
    fun `successive lyric titles keep one song identity`() {
        val normalizer = SuperLyricMetaNormalizer()
        val before = normalizer.resolve("晴天", "周杰伦", null)

        val firstLine = normalizer.resolve("故事的小黄花", "晴天-周杰伦", "故事的小黄花")
        val secondLine = normalizer.resolve("从出生那年就飘着", "晴天-周杰伦", "从出生那年就飘着")

        assertEquals(before.songKey, firstLine.songKey)
        assertEquals(before.songKey, secondLine.songKey)
        assertEquals("晴天", secondLine.displayTitle)
        assertEquals("周杰伦", secondLine.displayArtist)
    }

    @Test
    fun `spaces and unicode dash variants are accepted`() {
        val separators = listOf(
            " - ", " ‐ ", " ‑ ", " – ", " — ", " ― ", " − ", " － ",
        )

        separators.forEach { separator ->
            val normalizer = SuperLyricMetaNormalizer()
            val before = normalizer.resolve("漠河舞厅", "柳爽", null)
            val during = normalizer.resolve(
                rawTitle = "我从没有见过极光出现的村落",
                rawArtist = "漠河舞厅${separator}柳爽",
                currentLyric = "我从没有见过极光出现的村落",
            )

            assertEquals("separator=$separator", "漠河舞厅", during.displayTitle)
            assertEquals("separator=$separator", "柳爽", during.displayArtist)
            assertEquals("separator=$separator", before.songKey, during.songKey)
        }
    }

    @Test
    fun `stable pair anchors dashed song and artist names`() {
        val normalizer = SuperLyricMetaNormalizer()
        val before = normalizer.resolve("A-B", "AC-DC", null)

        val during = normalizer.resolve(
            rawTitle = "a lyric line",
            rawArtist = "A-B—AC-DC",
            currentLyric = "a lyric line",
        )

        assertEquals("A-B", during.displayTitle)
        assertEquals("AC-DC", during.displayArtist)
        assertEquals(before.songKey, during.songKey)
    }

    @Test
    fun `ordinary artist containing a dash is never split before lyrics`() {
        val result = SuperLyricMetaNormalizer().resolve(
            rawTitle = "The Sound of Silence",
            rawArtist = "Simon - Garfunkel",
            currentLyric = null,
        )

        assertEquals("The Sound of Silence", result.displayTitle)
        assertEquals("Simon - Garfunkel", result.displayArtist)
        assertNotNull(result.songKey)
    }

    @Test
    fun `current lyric permits a composite to bootstrap a mid-song session`() {
        val result = SuperLyricMetaNormalizer().resolve(
            rawTitle = "current line",
            rawArtist = "Song — Singer",
            currentLyric = " current  line ",
        )

        assertEquals("Song", result.displayTitle)
        assertEquals("Singer", result.displayArtist)
        assertNotNull(result.songKey)
        assertTrue(result.titleIsLyric)
    }

    @Test
    fun `stable composite covers title update arriving before lyric callback`() {
        val normalizer = SuperLyricMetaNormalizer()
        val before = normalizer.resolve("Song", "Singer", null)

        val duringRace = normalizer.resolve(
            rawTitle = "next line",
            rawArtist = "Song-Singer",
            currentLyric = "previous line",
        )

        assertEquals("Song", duringRace.displayTitle)
        assertEquals("Singer", duringRace.displayArtist)
        assertEquals(before.songKey, duringRace.songKey)
        assertTrue(duringRace.titleIsLyric)
    }

    @Test
    fun `blank and one-sided updates do not clear a stable identity`() {
        val normalizer = SuperLyricMetaNormalizer()
        val stable = normalizer.resolve("Song", "Singer", null)

        assertEquals(stable, normalizer.resolve(null, "   ", null))

        val titleOnly = normalizer.resolve("a lyric", null, "a lyric")
        assertEquals("Song", titleOnly.displayTitle)
        assertEquals("Singer", titleOnly.displayArtist)
        assertEquals(stable.songKey, titleOnly.songKey)
        assertTrue(titleOnly.titleIsLyric)

        val artistOnly = normalizer.resolve(null, "Song-Singer", "a lyric")
        assertEquals("Song", artistOnly.displayTitle)
        assertEquals("Singer", artistOnly.displayArtist)
        assertEquals(stable.songKey, artistOnly.songKey)
    }

    @Test
    fun `same title with a different artist gets a distinct key after confirmation`() {
        val normalizer = SuperLyricMetaNormalizer()
        val original = normalizer.resolve("Intro", "Artist A", null)

        val transitional = normalizer.resolve("Intro", "Artist B", null)
        assertEquals(original.songKey, transitional.songKey)

        val switched = normalizer.resolve("Intro", "Artist B", null)
        assertEquals("Intro", switched.displayTitle)
        assertEquals("Artist B", switched.displayArtist)
        assertNotEquals(original.songKey, switched.songKey)
    }

    @Test
    fun `a full switch changing both fields is accepted immediately`() {
        val normalizer = SuperLyricMetaNormalizer()
        val first = normalizer.resolve("First", "One", null)

        val second = normalizer.resolve("Second", "Two", null)

        assertEquals("Second", second.displayTitle)
        assertEquals("Two", second.displayArtist)
        assertNotEquals(first.songKey, second.songKey)
    }

    @Test
    fun `initial partial display has no manufactured identity and survives empty polls`() {
        val normalizer = SuperLyricMetaNormalizer()

        val partial = normalizer.resolve("Only a title", null, null)
        assertEquals("Only a title", partial.displayTitle)
        assertNull(partial.displayArtist)
        assertNull(partial.songKey)

        assertEquals(partial, normalizer.resolve(null, null, null))
    }

    @Test
    fun `reset drops display and identity anchors`() {
        val normalizer = SuperLyricMetaNormalizer()
        normalizer.resolve("Song", "Singer", null)

        normalizer.reset()

        assertNull(normalizer.current.displayTitle)
        assertNull(normalizer.current.displayArtist)
        assertNull(normalizer.current.songKey)
        assertFalse(normalizer.current.titleIsLyric)
    }
}
