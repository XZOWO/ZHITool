package com.zhitool.rearlyric.lyric

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置持久化(SharedPreferences)。启动时 [load] 进 [RearConfigState]，改动时 [save] 落盘。
 */
object ConfigStore {
    private const val PREF = "zhi_config"
    private const val K_COVER = "cover"
    private const val K_COVER_SHAPE = "cover_shape"
    private const val K_FRAME_RATE = "frame_rate"
    private const val K_DYNAMIC = "dynamic_background"
    private const val K_TEXT_COLOR_MODE = "text_color_mode"
    private const val K_SECONDARY = "show_secondary"
    private const val K_TRANSLATION = "show_translation"
    private const val K_ROMA = "show_roma"
    private const val K_BOLD = "bold"
    private const val K_ITALIC = "italic"
    private const val K_FONT_WEIGHT = "font_weight"
    private const val K_GRADIENT = "gradient_progress"
    private const val K_RELATIVE_PROGRESS = "relative_progress"
    private const val K_RELATIVE_HIGHLIGHT = "relative_highlight"
    private const val K_SIMULATE_WORD = "simulate_word_timing"
    private const val K_SINK_ANIMATION = "sink_animation"
    private const val K_LYRIC_ANIMATION = "lyric_animation"
    private const val K_SAFE_LEFT = "safe_area_left"
    private const val K_SAFE_RIGHT = "safe_area_right"
    private const val K_LYRIC_TEXT_SIZE = "lyric_text_size"
    private const val K_LOCK_SIZE = "lock_size"
    private const val K_LOCK_OFFSET = "lock_offset"
    private const val K_TIME_OFFSET = "time_offset"
    private const val K_TIME_LINE_LEN = "time_line_length"
    private const val K_BATTERY_MODE = "battery_mode"
    private const val K_CHARGE_WAVE = "charge_wave"
    private const val K_BACKGROUND = "rear_background"
    private const val K_RHYTHM_INTENSITY = "rhythm_intensity"
    private const val K_RHYTHM_DECAY = "rhythm_decay"
    private const val K_SPECTRUM_HEIGHT = "spectrum_height"
    private const val K_GLOW_PERC = "glow_perc_gain"
    private const val K_GLOW_HARM = "glow_harm_gain"
    private const val K_LYRIC_GLOW = "lyric_glow"
    private const val K_LYRIC_RHYTHM = "lyric_rhythm"
    private const val K_CONTROL_RHYTHM = "control_rhythm"
    private const val K_UI_RHYTHM_INTENSITY = "ui_rhythm_intensity"
    private const val K_UI_RHYTHM_DECAY = "ui_rhythm_decay"
    private const val K_LYRIC_GLOW_INTENSITY = "lyric_glow_intensity"
    private const val K_GLOW_INTENSITY_V2 = "glow_intensity_v2"
    private const val K_CFG_VERSION = "config_version"
    /** 配置版本：升到 1 时一次性重定基（安全区/小锁/时间偏移归 0）+ 刷新绝对默认（字号 59/小锁 14/细线 32）。 */
    private const val CFG_VERSION = 1
    private const val K_DEFAULTS_VERSION = "defaults_version"
    /** 律动默认版本：升到 1 时把律动相关项一次性刷成新默认（背景=律动/强度20/回落慢/增益300/500）。 */
    private const val DEFAULTS_VERSION = 1
    private const val K_ONLY_SELECTED = "only_selected"
    private const val K_SELECTED_APPS = "selected_apps"
    private const val K_PACKAGE_STYLES = "package_styles"
    private const val K_PROJECTION_ENABLED = "projection_enabled"
    private const val K_TOOL_PROJECTION_ENABLED = "tool_projection_enabled"
    private const val K_LYRIC_SOURCE = "lyric_source"

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        RearConfigState.update(
            RearConfig(
                cover = CoverPosition.entries.getOrElse(sp.getInt(K_COVER, CoverPosition.LEFT.ordinal)) { CoverPosition.LEFT },
                coverShape = CoverShape.entries.getOrElse(sp.getInt(K_COVER_SHAPE, CoverShape.SQUARE.ordinal)) { CoverShape.SQUARE },
                frameRate = LyricFrameRate.entries.getOrElse(sp.getInt(K_FRAME_RATE, LyricFrameRate.FPS_120.ordinal)) { LyricFrameRate.FPS_120 },
                dynamicBackground = sp.getBoolean(K_DYNAMIC, true),
                textColorMode = TextColorMode.entries.getOrElse(sp.getInt(K_TEXT_COLOR_MODE, TextColorMode.DEFAULT.ordinal)) { TextColorMode.DEFAULT },
                showSecondary = sp.getBoolean(K_SECONDARY, true),
                showTranslation = sp.getBoolean(K_TRANSLATION, true),
                showRoma = sp.getBoolean(K_ROMA, true),
                bold = sp.getBoolean(K_BOLD, true),
                italic = sp.getBoolean(K_ITALIC, false),
                fontWeight = sp.getInt(K_FONT_WEIGHT, 700),
                gradientProgress = sp.getBoolean(K_GRADIENT, true),
                relativeProgress = sp.getBoolean(K_RELATIVE_PROGRESS, true),
                relativeHighlight = sp.getBoolean(K_RELATIVE_HIGHLIGHT, true),
                simulateWordTiming = sp.getBoolean(K_SIMULATE_WORD, true),
                sinkAnimation = sp.getBoolean(K_SINK_ANIMATION, true),
                lyricAnimation = LyricAnimation.entries.getOrElse(sp.getInt(K_LYRIC_ANIMATION, LyricAnimation.RANDOM_RISE.ordinal)) { LyricAnimation.RANDOM_RISE },
                safeAreaLeft = sp.getInt(K_SAFE_LEFT, 0),
                safeAreaRight = sp.getInt(K_SAFE_RIGHT, 0),
                lyricTextSize = sp.getInt(K_LYRIC_TEXT_SIZE, 59),
                lockSize = sp.getInt(K_LOCK_SIZE, 14),
                lockOffset = sp.getInt(K_LOCK_OFFSET, 0),
                timeOffset = sp.getInt(K_TIME_OFFSET, 0),
                timeLineLength = sp.getInt(K_TIME_LINE_LEN, 32),
                batteryMode = BatteryMode.entries.getOrElse(sp.getInt(K_BATTERY_MODE, BatteryMode.WHEN_CHARGING.ordinal)) { BatteryMode.WHEN_CHARGING },
                chargeWave = sp.getBoolean(K_CHARGE_WAVE, true),
                background = RearBackground.entries.getOrElse(sp.getInt(K_BACKGROUND, RearBackground.PULSE.ordinal)) { RearBackground.PULSE },
                rhythmIntensity = sp.getInt(K_RHYTHM_INTENSITY, 20),
                rhythmDecay = RhythmDecay.entries.getOrElse(sp.getInt(K_RHYTHM_DECAY, RhythmDecay.SLOW.ordinal)) { RhythmDecay.SLOW },
                spectrumHeight = sp.getInt(K_SPECTRUM_HEIGHT, 60),
                glowPercGain = sp.getInt(K_GLOW_PERC, 300),
                glowHarmGain = sp.getInt(K_GLOW_HARM, 500),
                lyricGlow = sp.getBoolean(K_LYRIC_GLOW, true),
                lyricRhythm = sp.getBoolean(K_LYRIC_RHYTHM, true),
                controlRhythm = sp.getBoolean(K_CONTROL_RHYTHM, true),
                uiRhythmIntensity = sp.getInt(K_UI_RHYTHM_INTENSITY, 20),
                uiRhythmDecay = RhythmDecay.entries.getOrElse(sp.getInt(K_UI_RHYTHM_DECAY, RhythmDecay.SLOW.ordinal)) { RhythmDecay.SLOW },
                lyricGlowIntensity = sp.getInt(K_LYRIC_GLOW_INTENSITY, 300),
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
        ToolProjectionState.update(sp.getBoolean(K_TOOL_PROJECTION_ENABLED, true))
        LyricSourceState.update(
            LyricSource.entries.getOrElse(sp.getInt(K_LYRIC_SOURCE, LyricSource.LYRICON.ordinal)) { LyricSource.LYRICON }
        )

        // 一次性迁移：旧版手动调出的偏移值（基于旧基准）会与新基准叠加，故重定基归 0，并把绝对项刷到新默认。
        if (sp.getInt(K_CFG_VERSION, 0) < CFG_VERSION) {
            fun recenter(c: RearConfig) = c.copy(
                safeAreaLeft = 0, safeAreaRight = 0, lockOffset = 0, timeOffset = 0,
                lyricTextSize = 59, lockSize = 14, timeLineLength = 32,
            )
            save(context, recenter(RearConfigState.current))
            if (PackageStyleState.current.isNotEmpty()) {
                savePackageStyles(context, PackageStyleState.current.mapValues { (_, s) -> s.copy(config = recenter(s.config)) })
            }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(K_CFG_VERSION, CFG_VERSION).apply()
        }

        // 一次性把律动相关项刷成新默认（用户指定：律动背景/强度20/回落慢/低音300/非低音500）。
        if (sp.getInt(K_DEFAULTS_VERSION, 0) < DEFAULTS_VERSION) {
            save(
                context,
                RearConfigState.current.copy(
                    background = RearBackground.PULSE,
                    rhythmIntensity = 20,
                    rhythmDecay = RhythmDecay.SLOW,
                    glowPercGain = 300,
                    glowHarmGain = 500,
                ),
            )
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(K_DEFAULTS_VERSION, DEFAULTS_VERSION).apply()
        }

        // 一次性把"歌词发光强度"默认由 100 提到 300：只在用户从未改过（仍是旧默认 100）时提升，
        // 尊重已自定义的值；不影响其它律动设置。
        if (!sp.getBoolean(K_GLOW_INTENSITY_V2, false)) {
            if (RearConfigState.current.lyricGlowIntensity == 100) {
                save(context, RearConfigState.current.copy(lyricGlowIntensity = 300))
            }
            if (PackageStyleState.current.isNotEmpty()) {
                savePackageStyles(
                    context,
                    PackageStyleState.current.mapValues { (_, s) ->
                        if (s.config.lyricGlowIntensity == 100) s.copy(config = s.config.copy(lyricGlowIntensity = 300)) else s
                    },
                )
            }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_GLOW_INTENSITY_V2, true).apply()
        }
    }

    fun saveProjectionEnabled(context: Context, enabled: Boolean) {
        ProjectionState.update(enabled)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(K_PROJECTION_ENABLED, enabled)
            .apply()
    }

    fun saveToolProjectionEnabled(context: Context, enabled: Boolean) {
        ToolProjectionState.update(enabled)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(K_TOOL_PROJECTION_ENABLED, enabled)
            .apply()
    }

    fun saveLyricSource(context: Context, source: LyricSource) {
        LyricSourceState.update(source)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(K_LYRIC_SOURCE, source.ordinal)
            .apply()
    }

    fun save(context: Context, cfg: RearConfig) {
        RearConfigState.update(cfg)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putInt(K_COVER, cfg.cover.ordinal)
            putInt(K_COVER_SHAPE, cfg.coverShape.ordinal)
            putInt(K_FRAME_RATE, cfg.frameRate.ordinal)
            putBoolean(K_DYNAMIC, cfg.dynamicBackground)
            putInt(K_TEXT_COLOR_MODE, cfg.textColorMode.ordinal)
            putBoolean(K_SECONDARY, cfg.showSecondary)
            putBoolean(K_TRANSLATION, cfg.showTranslation)
            putBoolean(K_ROMA, cfg.showRoma)
            putBoolean(K_BOLD, cfg.bold)
            putBoolean(K_ITALIC, cfg.italic)
            putInt(K_FONT_WEIGHT, cfg.fontWeight)
            putBoolean(K_GRADIENT, cfg.gradientProgress)
            putBoolean(K_RELATIVE_PROGRESS, cfg.relativeProgress)
            putBoolean(K_RELATIVE_HIGHLIGHT, cfg.relativeHighlight)
            putBoolean(K_SIMULATE_WORD, cfg.simulateWordTiming)
            putBoolean(K_SINK_ANIMATION, cfg.sinkAnimation)
            putInt(K_LYRIC_ANIMATION, cfg.lyricAnimation.ordinal)
            putInt(K_SAFE_LEFT, cfg.safeAreaLeft)
            putInt(K_SAFE_RIGHT, cfg.safeAreaRight)
            putInt(K_LYRIC_TEXT_SIZE, cfg.lyricTextSize)
            putInt(K_LOCK_SIZE, cfg.lockSize)
            putInt(K_LOCK_OFFSET, cfg.lockOffset)
            putInt(K_TIME_OFFSET, cfg.timeOffset)
            putInt(K_TIME_LINE_LEN, cfg.timeLineLength)
            putInt(K_BATTERY_MODE, cfg.batteryMode.ordinal)
            putBoolean(K_CHARGE_WAVE, cfg.chargeWave)
            putInt(K_BACKGROUND, cfg.background.ordinal)
            putInt(K_RHYTHM_INTENSITY, cfg.rhythmIntensity)
            putInt(K_RHYTHM_DECAY, cfg.rhythmDecay.ordinal)
            putInt(K_SPECTRUM_HEIGHT, cfg.spectrumHeight)
            putInt(K_GLOW_PERC, cfg.glowPercGain)
            putInt(K_GLOW_HARM, cfg.glowHarmGain)
            putBoolean(K_LYRIC_GLOW, cfg.lyricGlow)
            putBoolean(K_LYRIC_RHYTHM, cfg.lyricRhythm)
            putBoolean(K_CONTROL_RHYTHM, cfg.controlRhythm)
            putInt(K_UI_RHYTHM_INTENSITY, cfg.uiRhythmIntensity)
            putInt(K_UI_RHYTHM_DECAY, cfg.uiRhythmDecay.ordinal)
            putInt(K_LYRIC_GLOW_INTENSITY, cfg.lyricGlowIntensity)
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
        put("cover", cfg.cover.ordinal)
        put("coverShape", cfg.coverShape.ordinal)
        put("frameRate", cfg.frameRate.ordinal)
        put("dynamicBackground", cfg.dynamicBackground)
        put("textColorMode", cfg.textColorMode.ordinal)
        put("showSecondary", cfg.showSecondary)
        put("showTranslation", cfg.showTranslation)
        put("showRoma", cfg.showRoma)
        put("bold", cfg.bold)
        put("italic", cfg.italic)
        put("fontWeight", cfg.fontWeight)
        put("gradientProgress", cfg.gradientProgress)
        put("relativeProgress", cfg.relativeProgress)
        put("relativeHighlight", cfg.relativeHighlight)
        put("background", cfg.background.ordinal)
        put("rhythmIntensity", cfg.rhythmIntensity)
        put("rhythmDecay", cfg.rhythmDecay.ordinal)
        put("spectrumHeight", cfg.spectrumHeight)
        put("glowPercGain", cfg.glowPercGain)
        put("glowHarmGain", cfg.glowHarmGain)
        put("lyricGlow", cfg.lyricGlow)
        put("lyricRhythm", cfg.lyricRhythm)
        put("controlRhythm", cfg.controlRhythm)
        put("uiRhythmIntensity", cfg.uiRhythmIntensity)
        put("uiRhythmDecay", cfg.uiRhythmDecay.ordinal)
        put("lyricGlowIntensity", cfg.lyricGlowIntensity)
        put("sinkAnimation", cfg.sinkAnimation)
        put("safeAreaLeft", cfg.safeAreaLeft)
        put("safeAreaRight", cfg.safeAreaRight)
        put("lyricTextSize", cfg.lyricTextSize)
        put("lockSize", cfg.lockSize)
        put("lockOffset", cfg.lockOffset)
        put("timeOffset", cfg.timeOffset)
        put("timeLineLength", cfg.timeLineLength)
    }

    private fun readConfig(obj: JSONObject?): RearConfig {
        if (obj == null) return RearConfig()
        return RearConfig(
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
            bold = obj.optBoolean("bold", true),
            italic = obj.optBoolean("italic", false),
            fontWeight = obj.optInt("fontWeight", 700),
            gradientProgress = obj.optBoolean("gradientProgress", true),
            relativeProgress = obj.optBoolean("relativeProgress", true),
            relativeHighlight = obj.optBoolean("relativeHighlight", true),
            background = RearBackground.entries.getOrElse(obj.optInt("background", RearBackground.PULSE.ordinal)) { RearBackground.PULSE },
            rhythmIntensity = obj.optInt("rhythmIntensity", 20),
            rhythmDecay = RhythmDecay.entries.getOrElse(obj.optInt("rhythmDecay", RhythmDecay.SLOW.ordinal)) { RhythmDecay.SLOW },
            spectrumHeight = obj.optInt("spectrumHeight", 60),
            glowPercGain = obj.optInt("glowPercGain", 300),
            glowHarmGain = obj.optInt("glowHarmGain", 500),
            lyricGlow = obj.optBoolean("lyricGlow", true),
            lyricRhythm = obj.optBoolean("lyricRhythm", true),
            controlRhythm = obj.optBoolean("controlRhythm", true),
            uiRhythmIntensity = obj.optInt("uiRhythmIntensity", 20),
            uiRhythmDecay = RhythmDecay.entries.getOrElse(obj.optInt("uiRhythmDecay", RhythmDecay.SLOW.ordinal)) { RhythmDecay.SLOW },
            lyricGlowIntensity = obj.optInt("lyricGlowIntensity", 300),
            sinkAnimation = obj.optBoolean("sinkAnimation", true),
            safeAreaLeft = obj.optInt("safeAreaLeft", 0),
            safeAreaRight = obj.optInt("safeAreaRight", 0),
            lyricTextSize = obj.optInt("lyricTextSize", 59),
            lockSize = obj.optInt("lockSize", 14),
            lockOffset = obj.optInt("lockOffset", 0),
            timeOffset = obj.optInt("timeOffset", 0),
            timeLineLength = obj.optInt("timeLineLength", 32),
        )
    }
}
