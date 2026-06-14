package com.zhitool.rearlyric.lyric

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置持久化(SharedPreferences)。启动时 [load] 进 [RearConfigState]，改动时 [save] 落盘。
 */
object ConfigStore {
    private const val PREF = "zhi_config"
    private const val K_DISPLAY_MODE = "display_mode"
    private const val K_ALIGN = "align"
    private const val K_COVER = "cover"
    private const val K_COVER_SHAPE = "cover_shape"
    private const val K_FRAME_RATE = "frame_rate"
    private const val K_DYNAMIC = "dynamic_background"
    private const val K_TEXT_COLOR_MODE = "text_color_mode"
    private const val K_SECONDARY = "show_secondary"
    private const val K_TRANSLATION = "show_translation"
    private const val K_ROMA = "show_roma"
    private const val K_FONT_SIZE = "font_size"
    private const val K_SECONDARY_SCALE = "secondary_scale"
    private const val K_BOLD = "bold"
    private const val K_ITALIC = "italic"
    private const val K_FONT_WEIGHT = "font_weight"
    private const val K_GRADIENT = "gradient_progress"
    private const val K_RELATIVE_PROGRESS = "relative_progress"
    private const val K_RELATIVE_HIGHLIGHT = "relative_highlight"
    private const val K_ONLY_SELECTED = "only_selected"
    private const val K_SELECTED_APPS = "selected_apps"
    private const val K_PACKAGE_STYLES = "package_styles"
    private const val K_PROJECTION_ENABLED = "projection_enabled"

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        RearConfigState.update(
            RearConfig(
                displayMode = LyricDisplayMode.entries.getOrElse(
                    sp.getInt(K_DISPLAY_MODE, LyricDisplayMode.FULL_LYRIC.ordinal)
                ) { LyricDisplayMode.FULL_LYRIC },
                align = LyricAlign.entries.getOrElse(sp.getInt(K_ALIGN, LyricAlign.CENTER.ordinal)) { LyricAlign.CENTER },
                cover = CoverPosition.entries.getOrElse(sp.getInt(K_COVER, CoverPosition.LEFT.ordinal)) { CoverPosition.LEFT },
                coverShape = CoverShape.entries.getOrElse(sp.getInt(K_COVER_SHAPE, CoverShape.SQUARE.ordinal)) { CoverShape.SQUARE },
                frameRate = LyricFrameRate.entries.getOrElse(sp.getInt(K_FRAME_RATE, LyricFrameRate.FPS_120.ordinal)) { LyricFrameRate.FPS_120 },
                dynamicBackground = sp.getBoolean(K_DYNAMIC, true),
                textColorMode = TextColorMode.entries.getOrElse(sp.getInt(K_TEXT_COLOR_MODE, TextColorMode.DEFAULT.ordinal)) { TextColorMode.DEFAULT },
                showSecondary = sp.getBoolean(K_SECONDARY, true),
                showTranslation = sp.getBoolean(K_TRANSLATION, true),
                showRoma = sp.getBoolean(K_ROMA, true),
                fontSize = sp.getInt(K_FONT_SIZE, 60),
                secondaryScale = sp.getFloat(K_SECONDARY_SCALE, 0.86f),
                bold = sp.getBoolean(K_BOLD, true),
                italic = sp.getBoolean(K_ITALIC, false),
                fontWeight = sp.getInt(K_FONT_WEIGHT, 700),
                gradientProgress = sp.getBoolean(K_GRADIENT, true),
                relativeProgress = sp.getBoolean(K_RELATIVE_PROGRESS, true),
                relativeHighlight = sp.getBoolean(K_RELATIVE_HIGHLIGHT, true),
            )
        )
        AppFilterState.update(
            AppFilter(
                onlySelected = sp.getBoolean(K_ONLY_SELECTED, false),
                selectedApps = sp.getStringSet(K_SELECTED_APPS, emptySet())?.toSet() ?: emptySet(),
            )
        )
        PackageStyleState.update(loadPackageStyles(sp.getString(K_PACKAGE_STYLES, null)))
        ProjectionState.update(sp.getBoolean(K_PROJECTION_ENABLED, true))
    }

    fun saveProjectionEnabled(context: Context, enabled: Boolean) {
        ProjectionState.update(enabled)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(K_PROJECTION_ENABLED, enabled)
            .apply()
    }

    fun save(context: Context, cfg: RearConfig) {
        RearConfigState.update(cfg)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putInt(K_DISPLAY_MODE, cfg.displayMode.ordinal)
            putInt(K_ALIGN, cfg.align.ordinal)
            putInt(K_COVER, cfg.cover.ordinal)
            putInt(K_COVER_SHAPE, cfg.coverShape.ordinal)
            putInt(K_FRAME_RATE, cfg.frameRate.ordinal)
            putBoolean(K_DYNAMIC, cfg.dynamicBackground)
            putInt(K_TEXT_COLOR_MODE, cfg.textColorMode.ordinal)
            putBoolean(K_SECONDARY, cfg.showSecondary)
            putBoolean(K_TRANSLATION, cfg.showTranslation)
            putBoolean(K_ROMA, cfg.showRoma)
            putInt(K_FONT_SIZE, cfg.fontSize)
            putFloat(K_SECONDARY_SCALE, cfg.secondaryScale)
            putBoolean(K_BOLD, cfg.bold)
            putBoolean(K_ITALIC, cfg.italic)
            putInt(K_FONT_WEIGHT, cfg.fontWeight)
            putBoolean(K_GRADIENT, cfg.gradientProgress)
            putBoolean(K_RELATIVE_PROGRESS, cfg.relativeProgress)
            putBoolean(K_RELATIVE_HIGHLIGHT, cfg.relativeHighlight)
            apply()
        }
    }

    fun saveFilter(context: Context, filter: AppFilter) {
        AppFilterState.update(filter)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putBoolean(K_ONLY_SELECTED, filter.onlySelected)
            putStringSet(K_SELECTED_APPS, filter.selectedApps)
            apply()
        }
    }

    fun savePackageStyles(context: Context, styles: Map<String, PackageStyle>) {
        PackageStyleState.update(styles)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putString(K_PACKAGE_STYLES, writePackageStyles(styles))
            apply()
        }
    }

    private fun writePackageStyles(styles: Map<String, PackageStyle>): String {
        val array = JSONArray()
        styles.values.sortedBy { it.label.lowercase() }.forEach { style ->
            array.put(
                JSONObject().apply {
                    put("packageName", style.packageName)
                    put("label", style.label)
                    put("config", writeConfig(style.config))
                }
            )
        }
        return array.toString()
    }

    private fun loadPackageStyles(raw: String?): Map<String, PackageStyle> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val packageName = item.optString("packageName")
                    val label = item.optString("label", packageName)
                    if (packageName.isBlank()) continue
                    val config = readConfig(item.optJSONObject("config"))
                    put(packageName, PackageStyle(packageName = packageName, label = label, config = config))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeConfig(cfg: RearConfig): JSONObject = JSONObject().apply {
        put("displayMode", cfg.displayMode.ordinal)
        put("align", cfg.align.ordinal)
        put("cover", cfg.cover.ordinal)
        put("coverShape", cfg.coverShape.ordinal)
        put("frameRate", cfg.frameRate.ordinal)
        put("dynamicBackground", cfg.dynamicBackground)
        put("textColorMode", cfg.textColorMode.ordinal)
        put("showSecondary", cfg.showSecondary)
        put("showTranslation", cfg.showTranslation)
        put("showRoma", cfg.showRoma)
        put("fontSize", cfg.fontSize)
        put("secondaryScale", cfg.secondaryScale.toDouble())
        put("bold", cfg.bold)
        put("italic", cfg.italic)
        put("fontWeight", cfg.fontWeight)
        put("gradientProgress", cfg.gradientProgress)
        put("relativeProgress", cfg.relativeProgress)
        put("relativeHighlight", cfg.relativeHighlight)
    }

    private fun readConfig(obj: JSONObject?): RearConfig {
        if (obj == null) return RearConfig()
        return RearConfig(
            displayMode = LyricDisplayMode.entries.getOrElse(
                obj.optInt("displayMode", LyricDisplayMode.FULL_LYRIC.ordinal)
            ) { LyricDisplayMode.FULL_LYRIC },
            align = LyricAlign.entries.getOrElse(obj.optInt("align", LyricAlign.CENTER.ordinal)) { LyricAlign.CENTER },
            cover = CoverPosition.entries.getOrElse(obj.optInt("cover", CoverPosition.LEFT.ordinal)) { CoverPosition.LEFT },
            coverShape = CoverShape.entries.getOrElse(obj.optInt("coverShape", CoverShape.SQUARE.ordinal)) { CoverShape.SQUARE },
            frameRate = LyricFrameRate.entries.getOrElse(obj.optInt("frameRate", LyricFrameRate.FPS_120.ordinal)) { LyricFrameRate.FPS_120 },
            dynamicBackground = obj.optBoolean("dynamicBackground", true),
            textColorMode = TextColorMode.entries.getOrElse(
                obj.optInt("textColorMode", TextColorMode.DEFAULT.ordinal)
            ) { TextColorMode.DEFAULT },
            showSecondary = obj.optBoolean("showSecondary", true),
            showTranslation = obj.optBoolean("showTranslation", true),
            showRoma = obj.optBoolean("showRoma", true),
            fontSize = obj.optInt("fontSize", 60),
            secondaryScale = obj.optDouble("secondaryScale", 0.86).toFloat(),
            bold = obj.optBoolean("bold", true),
            italic = obj.optBoolean("italic", false),
            fontWeight = obj.optInt("fontWeight", 700),
            gradientProgress = obj.optBoolean("gradientProgress", true),
            relativeProgress = obj.optBoolean("relativeProgress", true),
            relativeHighlight = obj.optBoolean("relativeHighlight", true),
        )
    }
}
