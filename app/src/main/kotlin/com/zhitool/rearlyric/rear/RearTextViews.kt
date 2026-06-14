package com.zhitool.rearlyric.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.zhitool.rearlyric.lyric.LyricAlign
import com.zhitool.rearlyric.lyric.LyricColors
import com.zhitool.rearlyric.lyric.RearConfig
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlin.math.roundToInt

private const val HEADER_TITLE_MAX_SP = 40f
private const val HEADER_TITLE_MIN_SP = 4f
private const val LYRIC_MIN_SP = 8f
private const val MAX_LYRIC_LINES = 5
private const val MAX_MAIN_LYRIC_SP = 60f
private const val TITLE_ARTIST_GAP_DP = 6f
private const val MAIN_SECONDARY_GAP_DP = 5f
/** 歌名字号上限：按"有封面时的可用宽度 + 5 个汉字"反算，避免短歌名字号过大。 */
private const val TITLE_CAP_CHARS = 5
/** 副句"演唱前提前量"：带逐字时间的和声在自己开口前这么久才顶入显示。 */
private const val SEC_LEAD_MS = 300L
/** 副句顶入时主句近似缩小量（reveal 0→1 从 1+量 缩到 1，配合上移营造"被顶上去"）。 */
private const val MAIN_REVEAL_SHRINK = 0.08f
/** 默认白字模式：已读文字透明度（<1 透出背景色，不是死白）。仅 DEFAULT 配色生效。 */
private const val READ_TEXT_ALPHA = 0.82f
/** 渐变高亮：前沿羽化宽度（sp）。 */
private const val GRADIENT_SOFT_SP = 26f
/** 渐变高亮 alpha 蒙版色（仅 alpha 有意义：不透明 → 透明）。 */
private val HIGHLIGHT_MASK_COLORS = intArrayOf(-0x1, 0x00FFFFFF)

internal class SongInfoBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(6f), 0f, dp(1.5f), Color.argb(112, 0, 0, 0))
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(96, 0, 0, 0))
    }

    private var titleLayout: StaticLayout? = null
    private var artistLayout: StaticLayout? = null

    private var titleText: String = "未知歌曲"
    private var artistText: String = "未知歌手"

    /** 有封面时歌名可用宽度（px，由 Compose 侧按"始终预留封面"算好传入），用于字号上限。 */
    private var titleSizingWidthPx: Int = 0

    fun bind(song: Song?, titleSizingWidthPx: Int) {
        val newTitle = song?.name?.takeIf { it.isNotBlank() } ?: "未知歌曲"
        val newArtist = song?.artist?.takeIf { it.isNotBlank() } ?: "未知歌手"
        val widthChanged = titleSizingWidthPx != this.titleSizingWidthPx
        if (newTitle == titleText && newArtist == artistText && !widthChanged) return
        titleText = newTitle
        artistText = newArtist
        this.titleSizingWidthPx = titleSizingWidthPx
        rebuildLayouts()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayouts()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val title = titleLayout ?: return
        val artist = artistLayout ?: return
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(0f)
        val gap = dp(TITLE_ARTIST_GAP_DP)
        val blockHeight = title.height + gap + artist.height
        val blockTop = contentTop + ((contentHeight - blockHeight).coerceAtLeast(0f) / 2f)
        val titleTop = blockTop
        val artistTop = titleTop + title.height + gap

        canvas.save()
        canvas.translate(contentLeft, titleTop)
        title.draw(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(contentLeft, artistTop)
        artist.draw(canvas)
        canvas.restore()
    }

    private fun rebuildLayouts() {
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val gap = dp(TITLE_ARTIST_GAP_DP).roundToInt()

        // 字号上限：以"有封面时的可用宽度排满 5 个汉字"为准，短歌名也不会被放得过大。
        var candidateSize = HEADER_TITLE_MAX_SP.coerceAtMost(titleSizeCapSp())
        var bestTitle: StaticLayout? = null
        var bestArtist: StaticLayout? = null

        while (candidateSize >= HEADER_TITLE_MIN_SP) {
            titlePaint.textSize = sp(candidateSize)
            artistPaint.textSize = titlePaint.textSize * 0.8f
            val title = buildLayout(
                text = titleText,
                paint = titlePaint,
                width = contentWidth,
                align = Layout.Alignment.ALIGN_NORMAL,
                maxLines = Int.MAX_VALUE,
            )
            val artist = buildLayout(
                text = artistText,
                paint = artistPaint,
                width = contentWidth,
                align = Layout.Alignment.ALIGN_NORMAL,
                maxLines = Int.MAX_VALUE,
            )
            bestTitle = title
            bestArtist = artist
            if (title.height + gap + artist.height <= contentHeight) break
            candidateSize -= 1f
        }

        titleLayout = bestTitle
        artistLayout = bestArtist
    }

    /** 找出"5 个汉字恰好填满有封面可用宽度"对应的最大字号，作为歌名字号上限。 */
    private fun titleSizeCapSp(): Float {
        val refWidth = (if (titleSizingWidthPx > 0) titleSizingWidthPx
        else (width - paddingLeft - paddingRight)).coerceAtLeast(1)
        val sample = "测".repeat(TITLE_CAP_CHARS)
        var size = HEADER_TITLE_MAX_SP
        while (size > HEADER_TITLE_MIN_SP) {
            titlePaint.textSize = sp(size)
            if (titlePaint.measureText(sample) <= refWidth) break
            size -= 1f
        }
        return size
    }

    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        align: Layout.Alignment,
        maxLines: Int,
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}

internal class RearLyricRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val mainBasePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(108, 255, 255, 255)
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(7f), 0f, dp(2f), Color.argb(110, 0, 0, 0))
    }
    private val secondaryBasePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        isSubpixelText = true
        isLinearText = true
        setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(90, 0, 0, 0))
    }
    /** 渐变高亮前沿羽化：saveLayer 内 DST_IN 横向 alpha 渐变蒙版。 */
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstInXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    private var config: RearConfig = RearConfig()
    private var colors: LyricColors = LyricColors.Default
    private var song: Song? = null
    private var positionMs: Long = 0L
    private var highlightShader: Shader? = null
    private var highlightShaderKey: Pair<Int, LyricColors>? = null

    private var currentLine: RichLyricLine? = null
    private var mainLayout: StaticLayout? = null
    private var secondaryLayout: StaticLayout? = null
    private var secondaryData: SecondaryData? = null
    private var lyricTop = 0f
    private var secondaryTop = 0f
    private var previewLeadMs: Long = 300L
    /** 当前主句+副句内容签名（主句/副句文本或抢唱句变化才重排版）。 */
    private var contentSig: String? = null
    /** 副句开始顶入的位置时刻（带逐字时间=首字开口前 SEC_LEAD_MS；翻译/罗马音=随主句；抢唱=立即）。 */
    private var secRevealThresholdMs: Long = Long.MAX_VALUE
    private var secondaryRevealSignature: String? = null
    private var secondaryRevealFrom = 0f
    private var secondaryRevealTarget = 0f
    private var secondaryRevealStartedAt = 0L
    private var secondaryRevealProgress = 0f

    fun bind(
        song: Song?,
        positionMs: Long,
        config: RearConfig,
        colors: LyricColors,
        previewLeadMs: Long,
    ) {
        val layoutDirty = this.song !== song || this.config != config || this.colors != colors
        this.song = song
        this.positionMs = positionMs
        this.config = config
        this.colors = colors
        this.previewLeadMs = previewLeadMs
        if (layoutDirty) {
            rebuildLayouts(forceLine = true)
        } else {
            rebuildLayouts(forceLine = false)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayouts(forceLine = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val main = mainLayout ?: return
        val line = currentLine ?: return
        val reveal = currentSecondaryRevealProgress()
        layoutPositions(reveal)
        val mainProgress = line.progressAt(positionMs, main, mainBasePaint)
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        // 副句顶入时主句近似缩小（配合上移，营造"被顶上去"）。
        val mainScale = if (secondaryLayout != null) lerp(1f + MAIN_REVEAL_SHRINK, 1f, reveal) else 1f

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), lyricTop)
        if (mainScale != 1f) {
            // 居左/居右行缩放时锚定对应边，不向两侧侵占；居中才以中心缩放。
            val pivotX = when (config.align) {
                LyricAlign.LEFT -> 0f
                LyricAlign.RIGHT -> contentWidth
                LyricAlign.CENTER -> contentWidth / 2f
            }
            canvas.scale(mainScale, mainScale, pivotX, main.height / 2f)
        }
        mainBasePaint.color = Color.argb(116, 255, 255, 255)
        mainBasePaint.setShadowLayer(dp(7f), 0f, dp(2f), Color.argb(110, 0, 0, 0))
        main.draw(canvas)
        mainBasePaint.color = highlightColor()
        // 默认白字模式：已读文字降点透明度，透出背景色。
        if (config.textColorMode == com.zhitool.rearlyric.lyric.TextColorMode.DEFAULT) {
            mainBasePaint.alpha = (255f * READ_TEXT_ALPHA).roundToInt().coerceIn(0, 255)
        }
        val mainShader = highlightShaderOrNull()
        mainBasePaint.shader = mainShader
        // shader（封面渐变）与 setShadowLayer 同时存在时，硬件加速会把阴影画成一块
        // 跟随进度裁剪区的渐变色矩形（Android 已知 bug）。底层暗色文字已带阴影，这里清掉即可。
        if (mainShader == null) {
            mainBasePaint.setShadowLayer(dp(8f), 0f, dp(2f), Color.argb(128, 0, 0, 0))
        } else {
            mainBasePaint.clearShadowLayer()
        }
        drawHighlightedLayout(canvas, main, mainProgress)
        mainBasePaint.shader = null
        canvas.restore()

        val secondary = secondaryLayout
        val secData = secondaryData
        if (secondary != null && secData != null && reveal > 0.001f) {
            val secondaryProgress = secData.progressAt(positionMs, line, secondary, secondaryBasePaint)
            val secondaryAlpha = (92f * reveal).roundToInt().coerceIn(0, 255)
            val highlightAlpha = (255f * reveal).roundToInt().coerceIn(0, 255)
            val extraDownShift = dp(16f) * (1f - reveal)
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), secondaryTop + extraDownShift)
            secondaryBasePaint.color = Color.argb(secondaryAlpha, 255, 255, 255)
            secondaryBasePaint.setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(90, 0, 0, 0))
            secondary.draw(canvas)
            val secHighlightAlpha = if (config.textColorMode == com.zhitool.rearlyric.lyric.TextColorMode.DEFAULT) {
                (highlightAlpha * READ_TEXT_ALPHA).roundToInt().coerceIn(0, 255)
            } else {
                highlightAlpha
            }
            secondaryBasePaint.color = withAlpha(highlightColor(), secHighlightAlpha)
            val secShader = highlightShaderOrNull()
            secondaryBasePaint.shader = secShader
            if (secShader == null) {
                secondaryBasePaint.setShadowLayer(dp(5f), 0f, dp(1.5f), Color.argb(96, 0, 0, 0))
            } else {
                secondaryBasePaint.clearShadowLayer()
            }
            drawHighlightedLayout(canvas, secondary, secondaryProgress * reveal)
            secondaryBasePaint.shader = null
            canvas.restore()
        }
    }

    private fun rebuildLayouts(forceLine: Boolean) {
        val resolved = resolveLines(song, positionMs)
        val line = resolved.main
        val secondary = resolveSecondaryContent(resolved)
        val sig = "${line?.begin}|${line?.text}|${secondary?.text}|${resolved.overlapNext?.begin}"
        if (forceLine || sig != contentSig || (mainLayout == null && line != null)) {
            contentSig = sig
            currentLine = line
            secondaryData = secondary
            secRevealThresholdMs = computeSecThreshold(resolved, secondary)
            buildLineLayouts(line, secondary)
        }
        // 每帧更新副句揭示目标：过阈值才顶入（抢唱立即；和声开口前 0.3s；翻译/罗马音随主句）。
        val active = secondaryData != null && positionMs >= secRevealThresholdMs
        updateSecondaryReveal(currentLine, secondaryData, active)
    }

    private fun buildLineLayouts(line: RichLyricLine?, secondary: SecondaryData?) {
        if (line == null || line.text.isNullOrBlank()) {
            mainLayout = null
            secondaryLayout = null
            return
        }
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        applyPaints()
        val align = when (config.align) {
            LyricAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            LyricAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            LyricAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }

        val maxMainSp = config.fontSize.coerceAtMost(MAX_MAIN_LYRIC_SP.toInt()).toFloat()
        var size = maxMainSp
        var builtMain: StaticLayout? = null
        var builtSecondary: StaticLayout? = null

        while (size >= LYRIC_MIN_SP) {
            mainBasePaint.textSize = sp(size)
            val mainCandidate = buildLayout(
                text = line.text.orEmpty(),
                paint = mainBasePaint,
                width = contentWidth,
                align = align,
                maxLines = MAX_LYRIC_LINES,
            )
            val secondaryCandidate = secondary?.let {
                secondaryBasePaint.textSize = mainBasePaint.textSize * config.secondaryScale
                val remainingLines = (MAX_LYRIC_LINES - mainCandidate.lineCount).coerceAtLeast(1)
                buildLayout(
                    text = it.text,
                    paint = secondaryBasePaint,
                    width = contentWidth,
                    align = align,
                    maxLines = remainingLines,
                )
            }
            builtMain = mainCandidate
            builtSecondary = secondaryCandidate
            val totalHeight = mainCandidate.height + (secondaryCandidate?.height ?: 0) +
                if (secondaryCandidate != null) dp(MAIN_SECONDARY_GAP_DP).roundToInt() else 0
            val totalLines = mainCandidate.lineCount + (secondaryCandidate?.lineCount ?: 0)
            if (totalHeight <= contentHeight && totalLines <= MAX_LYRIC_LINES) break
            size -= 1f
        }

        mainLayout = builtMain
        secondaryLayout = builtSecondary
    }

    /** 每帧按当前 reveal 计算主/副句位置：副句顶入时主句上移（与副句合并居中）。 */
    private fun layoutPositions(reveal: Float) {
        val main = mainLayout ?: return
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val gapPx = if (secondaryLayout != null) dp(MAIN_SECONDARY_GAP_DP) else 0f
        val secBlock = (secondaryLayout?.height ?: 0) + gapPx
        val totalHeight = main.height + secBlock * reveal
        val expandedTop = paddingTop + ((contentHeight - totalHeight).coerceAtLeast(0f) / 2f)
        val collapsedTop = paddingTop + ((contentHeight - main.height).toFloat().coerceAtLeast(0f) / 2f)
        lyricTop = lerp(collapsedTop, expandedTop, reveal)
        secondaryTop = lyricTop + main.height + gapPx
    }

    private fun applyPaints() {
        val typeface = buildTypeface(config)
        mainBasePaint.typeface = typeface
        secondaryBasePaint.typeface = typeface
    }

    private fun drawHighlightedLayout(canvas: Canvas, layout: StaticLayout, progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped <= 0f) return
        if (clamped >= 1f) {
            layout.draw(canvas)
            return
        }
        val lineWidths = FloatArray(layout.lineCount) { index ->
            (layout.getLineRight(index) - layout.getLineLeft(index)).coerceAtLeast(0f)
        }
        val totalWidth = lineWidths.sum().coerceAtLeast(1f)
        var remainingWidth = totalWidth * clamped

        for (lineIndex in 0 until layout.lineCount) {
            val lineWidth = lineWidths[lineIndex]
            if (remainingWidth <= 0f || lineWidth <= 0f) break
            val lineLeft = layout.getLineLeft(lineIndex)
            val lineRight = layout.getLineRight(lineIndex)
            val lineTop = layout.getLineTop(lineIndex).toFloat()
            val lineBottom = layout.getLineBottom(lineIndex).toFloat()
            val highlightOnLine = remainingWidth.coerceAtMost(lineWidth)
            val clipRight = (lineLeft + highlightOnLine).coerceIn(lineLeft, lineRight)
            val partial = clipRight < lineRight - 0.5f

            if (config.gradientProgress && partial) {
                // 渐变高亮：当前行前沿用横向 alpha 渐变（DST_IN）羽化。
                val soft = sp(GRADIENT_SOFT_SP).coerceAtMost(highlightOnLine)
                canvas.saveLayer(lineLeft, lineTop, lineRight, lineBottom, null)
                canvas.save()
                canvas.clipRect(lineLeft, lineTop, lineRight, lineBottom)
                layout.draw(canvas)
                canvas.restore()
                maskPaint.shader = LinearGradient(
                    clipRight - soft, 0f, clipRight, 0f,
                    HIGHLIGHT_MASK_COLORS, null, Shader.TileMode.CLAMP,
                )
                maskPaint.xfermode = dstInXfermode
                canvas.drawRect(lineLeft, lineTop, lineRight, lineBottom, maskPaint)
                maskPaint.xfermode = null
                maskPaint.shader = null
                canvas.restore()
            } else {
                canvas.save()
                canvas.clipRect(lineLeft, lineTop, clipRight, lineBottom)
                layout.draw(canvas)
                canvas.restore()
            }
            remainingWidth -= lineWidth
        }
    }

    private fun highlightColor(): Int =
        if (config.textColorMode == com.zhitool.rearlyric.lyric.TextColorMode.DEFAULT) {
            Color.WHITE
        } else {
            colors.highlight.toArgb()
        }

    /** 提取封面渐变色：≥2 色时按词幕方式建横向渐变 shader（按内容宽缓存）。 */
    private fun highlightShaderOrNull(): Shader? {
        if (config.textColorMode != com.zhitool.rearlyric.lyric.TextColorMode.COVER_GRADIENT) return null
        val cols = colors.highlightGradient
        if (cols.size < 2) return null
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val key = contentWidth to colors
        if (key != highlightShaderKey) {
            highlightShaderKey = key
            highlightShader = LinearGradient(
                0f, 0f, contentWidth.toFloat(), 0f,
                IntArray(cols.size) { cols[it].toArgb() },
                null,
                Shader.TileMode.CLAMP,
            )
        }
        return highlightShader
    }

    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        align: Layout.Alignment,
        maxLines: Int,
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
    }

    /** 解析当前主句（最近开口的一句）。信息模式只显示一句，不做抢唱占副句位。 */
    private fun resolveLines(song: Song?, positionMs: Long): Resolved {
        val lyrics = song?.lyrics.orEmpty().filter { !it.text.isNullOrBlank() }.sortedBy { it.begin }
        if (lyrics.isEmpty()) return Resolved(null, null)
        var idx = -1
        for (i in lyrics.indices) {
            if (lyrics[i].begin <= positionMs) idx = i else break
        }
        if (idx < 0) {
            return Resolved(lyrics.firstOrNull { it.begin - positionMs <= previewLeadMs }, null)
        }
        return Resolved(lyrics[idx], null)
    }

    /** 副句内容：抢唱的下一句优先占副句位（按它自己时间走逐字进度），否则用主句自带副句。 */
    private fun resolveSecondaryContent(resolved: Resolved): SecondaryData? {
        resolved.overlapNext?.let { next ->
            return SecondaryData(text = next.text.orEmpty(), words = next.words, selfLine = next)
        }
        return resolveSecondary(resolved.main)
    }

    /** 副句开始顶入的位置时刻：抢唱=立即；带逐字时间的和声=首字开口前 SEC_LEAD_MS；翻译/罗马音=随主句。 */
    private fun computeSecThreshold(resolved: Resolved, secondary: SecondaryData?): Long {
        if (secondary == null) return Long.MAX_VALUE
        if (resolved.overlapNext != null) return Long.MIN_VALUE
        val firstBegin = secondary.words?.firstOrNull()?.begin
        val mainBegin = resolved.main?.begin ?: 0L
        return if (firstBegin != null) firstBegin - SEC_LEAD_MS else mainBegin
    }

    private fun resolveSecondary(line: RichLyricLine?): SecondaryData? {
        if (line == null || !config.showSecondary) return null
        return when {
            !line.secondary.isNullOrBlank() -> SecondaryData(
                text = line.secondary.orEmpty(),
                words = line.secondaryWords,
            )
            config.showTranslation && !line.translation.isNullOrBlank() -> SecondaryData(
                text = line.translation.orEmpty(),
                words = line.translationWords,
            )
            config.showRoma && !line.roma.isNullOrBlank() -> SecondaryData(
                text = line.roma.orEmpty(),
                words = null,
            )
            else -> null
        }
    }

    private fun RichLyricLine.progressAt(
        positionMs: Long,
        layout: StaticLayout,
        paint: TextPaint,
    ): Float {
        val totalWidth = layoutTotalWidth(layout)
        if (totalWidth <= 0f) return 0f
        val sourceWords = words
        if (!sourceWords.isNullOrEmpty()) {
            val totalWordWidth = sourceWords.sumOf { paint.measureText(it.text.orEmpty()).toDouble() }
                .toFloat()
                .coerceAtLeast(1f)
            var passedWidth = 0f
            sourceWords.forEach { word ->
                val wordText = word.text.orEmpty()
                val wordWidth = paint.measureText(wordText).coerceAtLeast(0f)
                when {
                    positionMs >= word.end -> passedWidth += wordWidth
                    positionMs <= word.begin -> Unit
                    else -> {
                        val duration = (word.end - word.begin).coerceAtLeast(1L)
                        // 相对进度关闭：当前词到点即整词点亮（t=1），无词内平滑扫过。
                        val t = if (config.relativeProgress) {
                            ((positionMs - word.begin).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        } else 1f
                        passedWidth += wordWidth * t
                        return (passedWidth / totalWordWidth).coerceIn(0f, 1f)
                    }
                }
            }
            return (passedWidth / totalWordWidth).coerceIn(0f, 1f)
        }
        return when {
            positionMs <= begin -> 0f
            positionMs >= end -> 1f
            else -> ((positionMs - begin).toFloat() / (end - begin).coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
    }

    private fun SecondaryData.progressAt(
        positionMs: Long,
        main: RichLyricLine,
        layout: StaticLayout,
        paint: TextPaint,
    ): Float {
        val sourceWords = words
        if (!sourceWords.isNullOrEmpty()) {
            val totalWordWidth = sourceWords.sumOf { paint.measureText(it.text.orEmpty()).toDouble() }
                .toFloat()
                .coerceAtLeast(1f)
            var passedWidth = 0f
            sourceWords.forEach { word ->
                val wordText = word.text.orEmpty()
                val wordWidth = paint.measureText(wordText).coerceAtLeast(0f)
                when {
                    positionMs >= word.end -> passedWidth += wordWidth
                    positionMs <= word.begin -> Unit
                    else -> {
                        val duration = (word.end - word.begin).coerceAtLeast(1L)
                        // 相对进度关闭：当前词到点即整词点亮（t=1），无词内平滑扫过。
                        val t = if (config.relativeProgress) {
                            ((positionMs - word.begin).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        } else 1f
                        passedWidth += wordWidth * t
                        return (passedWidth / totalWordWidth).coerceIn(0f, 1f)
                    }
                }
            }
            return (passedWidth / totalWordWidth).coerceIn(0f, 1f)
        }
        return (selfLine ?: main).progressAt(positionMs, layout, paint)
    }

    private fun layoutTotalWidth(layout: StaticLayout): Float =
        (0 until layout.lineCount).sumOf { index ->
            (layout.getLineRight(index) - layout.getLineLeft(index)).coerceAtLeast(0f).toDouble()
        }.toFloat()

    private fun updateSecondaryReveal(line: RichLyricLine?, secondary: SecondaryData?, active: Boolean) {
        val target = if (line != null && secondary != null && !secondary.text.isBlank() && active) 1f else 0f
        val signature = if (secondary != null) "${line?.begin}|${secondary.text}" else null
        if (signature == secondaryRevealSignature && target == secondaryRevealTarget) return
        secondaryRevealFrom = currentSecondaryRevealProgress()
        secondaryRevealTarget = target
        secondaryRevealSignature = signature
        secondaryRevealStartedAt = SystemClock.elapsedRealtime()
        secondaryRevealProgress = secondaryRevealFrom
    }

    private fun currentSecondaryRevealProgress(): Float {
        if (secondaryRevealFrom == secondaryRevealTarget) return secondaryRevealTarget
        val elapsed = (SystemClock.elapsedRealtime() - secondaryRevealStartedAt).coerceAtLeast(0L)
        val t = (elapsed / 600f).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        secondaryRevealProgress = lerp(secondaryRevealFrom, secondaryRevealTarget, eased)
        if (t >= 1f) {
            secondaryRevealFrom = secondaryRevealTarget
            secondaryRevealProgress = secondaryRevealTarget
        }
        return secondaryRevealProgress
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private fun buildTypeface(cfg: RearConfig): Typeface {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(Typeface.DEFAULT, cfg.fontWeight, cfg.italic)
            cfg.bold && cfg.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            cfg.bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            cfg.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private data class SecondaryData(
        val text: String,
        val words: List<LyricWord>?,
        /** 抢唱副句=下一句本身，按它自己的 begin/end 算进度（无逐字时间时）。 */
        val selfLine: RichLyricLine? = null,
    )

    /** 解析结果：主句 + 抢唱的下一句（占副句位待晋升）。 */
    private data class Resolved(
        val main: RichLyricLine?,
        val overlapNext: RichLyricLine?,
    )
}
