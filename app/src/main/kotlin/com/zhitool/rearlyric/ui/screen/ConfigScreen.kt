package com.zhitool.rearlyric.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhitool.rearlyric.ui.components.DropdownOption
import com.zhitool.rearlyric.ui.components.DropdownPreference
import com.zhitool.rearlyric.ui.components.NumberTextField
import com.zhitool.rearlyric.ui.components.OverlayDialog
import com.zhitool.rearlyric.lyric.AppFilterState
import com.zhitool.rearlyric.lyric.ConfigStore
import com.zhitool.rearlyric.lyric.CoverPosition
import com.zhitool.rearlyric.lyric.CoverShape
import com.zhitool.rearlyric.lyric.LyricFrameRate
import com.zhitool.rearlyric.lyric.LyricRenderMetrics
import com.zhitool.rearlyric.lyric.PackageStyle
import com.zhitool.rearlyric.lyric.PackageStyleState
import com.zhitool.rearlyric.lyric.RearConfig
import com.zhitool.rearlyric.lyric.RearConfigState
import com.zhitool.rearlyric.lyric.BatteryMode
import com.zhitool.rearlyric.lyric.LyricAnimation
import com.zhitool.rearlyric.lyric.LyricSource
import com.zhitool.rearlyric.lyric.LyricSourceState
import com.zhitool.rearlyric.lyric.LyricStyleMode
import com.zhitool.rearlyric.lyric.LyricStyleState
import com.zhitool.rearlyric.lyric.StaggerConfigState
import com.zhitool.rearlyric.lyric.RearBackground
import com.zhitool.rearlyric.lyric.RhythmDecay
import com.zhitool.rearlyric.lyric.TextColorMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ConfigScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val baseConfig by RearConfigState.flow.collectAsState()
    val filter by AppFilterState.flow.collectAsState()
    val packageStyles by PackageStyleState.flow.collectAsState()
    val lyricSource by LyricSourceState.flow.collectAsState()
    val lyricStyle by LyricStyleState.flow.collectAsState()
    val staggerCfg by StaggerConfigState.flow.collectAsState()
    // 错位交替（星空）样式：歌词页只显示属于该样式的配置，默认样式的整套配置全部隐藏。
    val staggerMode = lyricStyle == LyricStyleMode.STAGGER_ALTERNATE
    // 星空样式组的数字输入弹窗（默认样式的弹窗在 RearConfigEditorContent 内部，各管各的）。
    var staggerDialog by remember { mutableStateOf<ConfigDialogState?>(null) }

    var showFilterPicker by rememberSaveable { mutableStateOf(false) }
    var showPackagePicker by rememberSaveable { mutableStateOf(false) }
    var editingPackage by remember { mutableStateOf<PackageStyle?>(null) }

    if (editingPackage != null) {
        PackageStyleConfigScreen(
            contentPadding = contentPadding,
            packageStyle = editingPackage!!,
            lyricSource = lyricSource,
            onBack = { editingPackage = null },
            onSave = { style ->
                ConfigStore.savePackageStyles(
                    context,
                    packageStyles.toMutableMap().apply { put(style.packageName, style) }
                )
                editingPackage = style
            },
            onDelete = { style ->
                ConfigStore.savePackageStyles(
                    context,
                    packageStyles.toMutableMap().apply { remove(style.packageName) }
                )
                editingPackage = null
            },
        )
        return
    }

    if (showFilterPicker) {
        AppPickerScreen(
            contentPadding = contentPadding,
            onBack = { showFilterPicker = false },
            mode = AppPickerMode.FILTER,
            filter = filter,
            onFilterToggle = { pkg, checked ->
                val next = if (checked) filter.selectedApps + pkg else filter.selectedApps - pkg
                ConfigStore.saveFilter(context, filter.copy(selectedApps = next))
            },
        )
        return
    }

    if (showPackagePicker) {
        AppPickerScreen(
            contentPadding = contentPadding,
            onBack = { showPackagePicker = false },
            mode = AppPickerMode.PACKAGE_STYLE,
            onAppPicked = { app ->
                showPackagePicker = false
                editingPackage = packageStyles[app.pkg]
                    ?: PackageStyle(packageName = app.pkg, label = app.label)
            },
        )
        return
    }

    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = { TopAppBar(title = "歌词", scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item { SmallTitle("歌词样式") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    DropdownPreference(
                        title = "歌词样式",
                        summary = when (lyricStyle) {
                            LyricStyleMode.DEFAULT -> "默认样式"
                            LyricStyleMode.STAGGER_ALTERNATE -> "错位交替（流动星空 + 错落漂浮）"
                        },
                        selectedValue = lyricStyle,
                        options = listOf(
                            DropdownOption("默认样式", LyricStyleMode.DEFAULT),
                            DropdownOption("错位交替", LyricStyleMode.STAGGER_ALTERNATE),
                        ),
                        onSelected = { ConfigStore.saveLyricStyle(context, it) },
                    )
                }
            }

            if (staggerMode) {
                // 星空样式独立配置（与默认样式的基础配置完全不通用）。
                item { SmallTitle("星空样式") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        SwitchPreference(
                            title = "时间显示",
                            summary = "右上角时钟（位置同默认模式）",
                            checked = staggerCfg.showClock,
                            onCheckedChange = {
                                ConfigStore.saveStagger(context, staggerCfg.copy(showClock = it))
                            },
                        )
                        ArrowPreference(
                            title = "字体大小",
                            summary = "${staggerCfg.textSizePercent}%（100 为基准）",
                            onClick = {
                                staggerDialog = ConfigDialogState.IntInput(
                                    "字体大小", staggerCfg.textSizePercent, "%", false,
                                ) {
                                    ConfigStore.saveStagger(
                                        context, staggerCfg.copy(textSizePercent = it.coerceIn(40, 200)),
                                    )
                                }
                            },
                        )
                        SwitchPreference(
                            title = "歌词发光",
                            summary = "当前句随鼓点发光（root 内录驱动）",
                            checked = staggerCfg.lyricGlow,
                            onCheckedChange = {
                                ConfigStore.saveStagger(context, staggerCfg.copy(lyricGlow = it))
                            },
                        )
                        if (staggerCfg.lyricGlow) {
                            ArrowPreference(
                                title = "发光强度",
                                summary = "${staggerCfg.lyricGlowIntensity}%（0–300）",
                                onClick = {
                                    staggerDialog = ConfigDialogState.IntInput(
                                        "发光强度", staggerCfg.lyricGlowIntensity, "%", false,
                                    ) {
                                        ConfigStore.saveStagger(
                                            context, staggerCfg.copy(lyricGlowIntensity = it.coerceIn(0, 300)),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                // 错位交替仍实际借用这些设备级参数；把无关的默认样式配置隐藏，只露出有效项。
                item { SmallTitle("星空通用调节") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        DropdownPreference(
                            title = "背屏刷新率",
                            summary = frameRateLabel(baseConfig.frameRate),
                            selectedValue = baseConfig.frameRate,
                            options = listOf(
                                DropdownOption("120 帧", LyricFrameRate.FPS_120),
                                DropdownOption("60 帧（省电）", LyricFrameRate.FPS_60),
                            ),
                            onSelected = { ConfigStore.save(context, baseConfig.copy(frameRate = it)) },
                        )
                        ArrowPreference(
                            title = "左安全区",
                            summary = baseConfig.safeAreaLeft.toString(),
                            onClick = {
                                staggerDialog = ConfigDialogState.IntInput(
                                    "左安全区", baseConfig.safeAreaLeft, "步", true,
                                ) { ConfigStore.save(context, baseConfig.copy(safeAreaLeft = it)) }
                            },
                        )
                        ArrowPreference(
                            title = "右安全区",
                            summary = baseConfig.safeAreaRight.toString(),
                            onClick = {
                                staggerDialog = ConfigDialogState.IntInput(
                                    "右安全区", baseConfig.safeAreaRight, "步", true,
                                ) { ConfigStore.save(context, baseConfig.copy(safeAreaRight = it)) }
                            },
                        )
                        if (staggerCfg.lyricGlow) {
                            DropdownPreference(
                                title = "发光回落速度",
                                summary = rhythmDecayLabel(baseConfig.uiRhythmDecay),
                                selectedValue = baseConfig.uiRhythmDecay,
                                options = RhythmDecay.entries.map { DropdownOption(rhythmDecayLabel(it), it) },
                                onSelected = { ConfigStore.save(context, baseConfig.copy(uiRhythmDecay = it)) },
                            )
                            ArrowPreference(
                                title = "低音高光增益",
                                summary = "${baseConfig.glowPercGain}%（低音反应强度）",
                                onClick = {
                                    staggerDialog = ConfigDialogState.IntInput(
                                        "低音高光增益", baseConfig.glowPercGain, "%", false,
                                    ) {
                                        ConfigStore.save(
                                            context,
                                            baseConfig.copy(glowPercGain = it.coerceIn(0, 1000)),
                                        )
                                    }
                                },
                            )
                            ArrowPreference(
                                title = "非低音高光增益",
                                summary = "${baseConfig.glowHarmGain}%（非低音反应强度）",
                                onClick = {
                                    staggerDialog = ConfigDialogState.IntInput(
                                        "非低音高光增益", baseConfig.glowHarmGain, "%", false,
                                    ) {
                                        ConfigStore.save(
                                            context,
                                            baseConfig.copy(glowHarmGain = it.coerceIn(0, 1000)),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                item {
                    RearConfigEditorContent(
                        title = "基础配置",
                        cfg = baseConfig,
                        lyricSource = lyricSource,
                        onUpdate = { ConfigStore.save(context, it) },
                    )
                }
            }

            item { SmallTitle("包配置") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "仅监听选中应用",
                        summary = "关闭则监听所有音乐应用",
                        checked = filter.onlySelected,
                        onCheckedChange = { ConfigStore.saveFilter(context, filter.copy(onlySelected = it)) },
                    )
                    if (filter.onlySelected) {
                        ArrowPreference(
                            title = "选择监听应用",
                            summary = "已选 ${filter.selectedApps.size} 个",
                            onClick = { showFilterPicker = true },
                        )
                    }
                    if (!staggerMode) {
                        ArrowPreference(
                            title = "添加单独配置",
                            summary = "为某个播放器单独设置歌词与封面样式",
                            onClick = { showPackagePicker = true },
                        )
                    }
                }
            }

            if (!staggerMode && packageStyles.isNotEmpty()) {
                item { SmallTitle("单独配置列表") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        packageStyles.values.sortedBy { it.label.lowercase() }.forEach { style ->
                            ArrowPreference(
                                title = style.label,
                                summary = style.packageName,
                                onClick = { editingPackage = style },
                            )
                        }
                    }
                }
            }

            if (!staggerMode && lyricSource == LyricSource.SUPERLYRIC) item { SmallTitle("歌词动画") }
            if (!staggerMode && lyricSource == LyricSource.SUPERLYRIC) item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    DropdownPreference(
                        title = "歌词动画",
                        summary = when (baseConfig.lyricAnimation) {
                            LyricAnimation.NONE -> "无"
                            LyricAnimation.RANDOM_RISE -> "随机升起"
                        },
                        selectedValue = baseConfig.lyricAnimation,
                        options = listOf(
                            DropdownOption("无", LyricAnimation.NONE),
                            DropdownOption("随机升起", LyricAnimation.RANDOM_RISE),
                        ),
                        onSelected = { ConfigStore.save(context, baseConfig.copy(lyricAnimation = it)) },
                    )
                }
            }

            item { SmallTitle("封面与来源") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    DropdownPreference(
                        title = "歌词数据源",
                        summary = when (lyricSource) {
                            LyricSource.LYRICON -> "词幕（lyricon 生态）"
                            LyricSource.SUPERLYRIC -> "SuperLyric（实时逐句）"
                        },
                        selectedValue = lyricSource,
                        options = listOf(
                            DropdownOption("词幕（lyricon 生态）", LyricSource.LYRICON),
                            DropdownOption("SuperLyric（实时逐句）", LyricSource.SUPERLYRIC),
                        ),
                        onSelected = { ConfigStore.saveLyricSource(context, it) },
                    )
                    ArrowPreference(
                        title = "封面来源",
                        summary = "LSPosed（SystemUI 媒体会话，字节广播实时推送）",
                        onClick = { },
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    ConfigDialogHost(
        state = staggerDialog,
        onDismiss = { staggerDialog = null },
    )
}

@Composable
private fun PackageStyleConfigScreen(
    contentPadding: PaddingValues,
    packageStyle: PackageStyle,
    lyricSource: LyricSource,
    onBack: () -> Unit,
    onSave: (PackageStyle) -> Unit,
    onDelete: (PackageStyle) -> Unit,
) {
    BackHandler(onBack = onBack)
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = packageStyle.label,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item {
                RearConfigEditorContent(
                    title = "单独配置",
                    cfg = packageStyle.config,
                    lyricSource = lyricSource,
                    onUpdate = { onSave(packageStyle.copy(config = it)) },
                )
            }
            item { SmallTitle("管理") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "删除此单独配置",
                        summary = packageStyle.packageName,
                        onClick = { onDelete(packageStyle) },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun RearConfigEditorContent(
    title: String,
    cfg: RearConfig,
    lyricSource: LyricSource,
    onUpdate: (RearConfig) -> Unit,
) {
    var dialogState by remember { mutableStateOf<ConfigDialogState?>(null) }

    SmallTitle(title)
    ConfigBasicCard(
        cfg = cfg,
        lyricSource = lyricSource,
        onUpdate = onUpdate,
    )
    SmallTitle("音乐律动")
    ConfigRhythmCard(
        cfg = cfg,
        lyricSource = lyricSource,
        onUpdate = onUpdate,
        onEditInt = { t, value, suffix, allowNegative, confirm ->
            dialogState = ConfigDialogState.IntInput(t, value, suffix, allowNegative, confirm)
        },
    )
    SmallTitle("文字与布局")
    ConfigTextCard(
        cfg = cfg,
        onUpdate = onUpdate,
        onEditInt = { t, value, suffix, allowNegative, confirm ->
            dialogState = ConfigDialogState.IntInput(t, value, suffix, allowNegative, confirm)
        },
    )
    SmallTitle("进度高亮")
    ConfigProgressCard(
        cfg = cfg,
        lyricSource = lyricSource,
        onUpdate = onUpdate,
    )
    SmallTitle("电池与充电")
    ConfigBatteryCard(
        cfg = cfg,
        onUpdate = onUpdate,
    )
    SmallTitle("安全区与微调")
    ConfigAdjustCard(
        cfg = cfg,
        lyricSource = lyricSource,
        onUpdate = onUpdate,
        onEditInt = { t, value, suffix, allowNegative, confirm ->
            dialogState = ConfigDialogState.IntInput(t, value, suffix, allowNegative, confirm)
        },
    )

    ConfigDialogHost(
        state = dialogState,
        onDismiss = { dialogState = null },
    )
}

/**
 * 音乐律动：背景模式（默认 / 律动 / 声谱，独立强度+回落）+ 三个各自独立的开关——歌词发光 / 歌词律动 /
 * 空间律动。任一开启即需内录音频（root 自授权，不弹窗），仅播放时驱动；声谱柱色跟随「封面取色背景」；
 * 缩放类（歌词律动+空间律动）共用「律动强度/律动回落速度」、发光单列「歌词发光强度」，两团增益全体共用。
 */
@Composable
private fun ConfigRhythmCard(
    cfg: RearConfig,
    lyricSource: LyricSource,
    onUpdate: (RearConfig) -> Unit,
    onEditInt: (title: String, value: Int, suffix: String, allowNegative: Boolean, onConfirm: (Int) -> Unit) -> Unit,
) {
    val lyricon = lyricSource == LyricSource.LYRICON
    val coverControlsAvailable = cfg.cover != CoverPosition.NONE
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        DropdownPreference(
            title = "背景",
            summary = when (cfg.background) {
                RearBackground.DEFAULT -> "默认背景"
                RearBackground.PULSE -> "律动背景（亮光随音乐波动）"
                RearBackground.SPECTRUM -> "声谱背景（默认背景 + 声谱律动）"
            },
            selectedValue = cfg.background,
            options = listOf(
                DropdownOption("默认背景", RearBackground.DEFAULT),
                DropdownOption("律动背景", RearBackground.PULSE),
                DropdownOption("声谱背景", RearBackground.SPECTRUM),
            ),
            onSelected = { onUpdate(cfg.copy(background = it)) },
        )
        if (cfg.background != RearBackground.DEFAULT) {
            ArrowPreference(
                title = "背景律动强度",
                summary = "${cfg.rhythmIntensity}（0–100，越大波动越夸张，仅背景）",
                onClick = {
                    onEditInt("背景律动强度", cfg.rhythmIntensity, "", false) {
                        onUpdate(cfg.copy(rhythmIntensity = it.coerceIn(0, 100)))
                    }
                },
            )
            DropdownPreference(
                title = "背景回落速度",
                summary = rhythmDecayLabel(cfg.rhythmDecay) + "（仅背景，回落平滑快慢）",
                selectedValue = cfg.rhythmDecay,
                options = RhythmDecay.entries.map { DropdownOption(rhythmDecayLabel(it), it) },
                onSelected = { onUpdate(cfg.copy(rhythmDecay = it)) },
            )
            if (cfg.background == RearBackground.SPECTRUM) {
                ArrowPreference(
                    title = "声谱高度",
                    summary = "${cfg.spectrumHeight}%（满格时柱高占屏比，可超 100）",
                    onClick = {
                        onEditInt("声谱高度", cfg.spectrumHeight, "%", false) {
                            onUpdate(cfg.copy(spectrumHeight = it.coerceIn(10, 300)))
                        }
                    },
                )
            }
        }
        // SuperLyric 单句渲染没有歌词光晕/整块歌词缩放，只显示真正生效的选项。
        if (lyricon) {
            SwitchPreference(
                title = "歌词发光",
                summary = "当前句高亮随鼓点叠加发光光晕；会额外启动内录，增加功耗",
                checked = cfg.lyricGlow,
                onCheckedChange = { onUpdate(cfg.copy(lyricGlow = it)) },
            )
            if (cfg.lyricGlow) {
                ArrowPreference(
                    title = "歌词发光强度",
                    summary = "${cfg.lyricGlowIntensity}%（只影响发光光晕强弱，不影响缩放幅度）",
                    onClick = {
                        onEditInt("歌词发光强度", cfg.lyricGlowIntensity, "%", false) {
                            onUpdate(cfg.copy(lyricGlowIntensity = it.coerceIn(0, 300)))
                        }
                    },
                )
            }
            SwitchPreference(
                title = "歌词律动",
                summary = "整屏歌词随节奏整体缩放（锚定当前句、不偏移）；会额外启动内录，增加功耗",
                checked = cfg.lyricRhythm,
                onCheckedChange = { onUpdate(cfg.copy(lyricRhythm = it)) },
            )
        }
        if (coverControlsAvailable) {
            SwitchPreference(
                title = "空间律动",
                summary = "封面与控制面板按钮随节奏缩放；会额外启动内录，增加功耗",
                checked = cfg.controlRhythm,
                onCheckedChange = { onUpdate(cfg.copy(controlRhythm = it)) },
            )
        }
        val lyricMotion = lyricon && cfg.lyricRhythm
        val controlMotion = coverControlsAvailable && cfg.controlRhythm
        if (lyricMotion || controlMotion) {
            ArrowPreference(
                title = "律动强度",
                summary = "${cfg.uiRhythmIntensity}（0–100，控制歌词/封面/按钮缩放幅度，独立于背景）",
                onClick = {
                    onEditInt("律动强度", cfg.uiRhythmIntensity, "", false) {
                        onUpdate(cfg.copy(uiRhythmIntensity = it.coerceIn(0, 100)))
                    }
                },
            )
            DropdownPreference(
                title = "律动回落速度",
                summary = rhythmDecayLabel(cfg.uiRhythmDecay) + "（仅歌词/空间律动，比背景整体更慢，同档更柔）",
                selectedValue = cfg.uiRhythmDecay,
                options = RhythmDecay.entries.map { DropdownOption(rhythmDecayLabel(it), it) },
                onSelected = { onUpdate(cfg.copy(uiRhythmDecay = it)) },
            )
        }
        // 背景律动/声谱或任一实际生效的 UI 律动都会读取这两项。
        val anyUiRhythm = (lyricon && (cfg.lyricGlow || cfg.lyricRhythm)) || controlMotion
        if (cfg.background != RearBackground.DEFAULT || anyUiRhythm) {
            ArrowPreference(
                title = "低音高光增益",
                summary = "${cfg.glowPercGain}%（低音反应强度）",
                onClick = {
                    onEditInt("低音高光增益", cfg.glowPercGain, "%", false) {
                        onUpdate(cfg.copy(glowPercGain = it.coerceIn(0, 1000)))
                    }
                },
            )
            ArrowPreference(
                title = "非低音高光增益",
                summary = "${cfg.glowHarmGain}%（非低音反应强度）",
                onClick = {
                    onEditInt("非低音高光增益", cfg.glowHarmGain, "%", false) {
                        onUpdate(cfg.copy(glowHarmGain = it.coerceIn(0, 1000)))
                    }
                },
            )
        }
    }
}

private fun rhythmDecayLabel(decay: RhythmDecay): String = when (decay) {
    RhythmDecay.VERY_FAST -> "极快"
    RhythmDecay.FAST -> "快"
    RhythmDecay.MEDIUM -> "中"
    RhythmDecay.SLOW -> "慢"
    RhythmDecay.VERY_SLOW -> "极慢"
}

private fun frameRateLabel(frameRate: LyricFrameRate): String = when (frameRate) {
    LyricFrameRate.FPS_120 -> "120 帧"
    LyricFrameRate.FPS_60 -> "60 帧（省电）"
}

@Composable
private fun ConfigBatteryCard(
    cfg: RearConfig,
    onUpdate: (RearConfig) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        DropdownPreference(
            title = "电池",
            summary = when (cfg.batteryMode) {
                BatteryMode.NONE -> "不显示"
                BatteryMode.WHEN_CHARGING -> "充电时显示"
                BatteryMode.ALWAYS -> "一直显示"
            },
            selectedValue = cfg.batteryMode,
            options = listOf(
                DropdownOption("不显示", BatteryMode.NONE),
                DropdownOption("充电时显示", BatteryMode.WHEN_CHARGING),
                DropdownOption("一直显示", BatteryMode.ALWAYS),
            ),
            onSelected = { onUpdate(cfg.copy(batteryMode = it)) },
        )
        SwitchPreference(
            title = "播放时充电动画",
            summary = "播放且充电时，歌词背后显示从下到上的液体波浪",
            checked = cfg.chargeWave,
            onCheckedChange = { onUpdate(cfg.copy(chargeWave = it)) },
        )
    }
}

@Composable
private fun ConfigBasicCard(
    cfg: RearConfig,
    lyricSource: LyricSource,
    onUpdate: (RearConfig) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        DropdownPreference(
            title = "封面位置",
            summary = when (cfg.cover) {
                CoverPosition.LEFT -> "左侧"
                CoverPosition.NONE -> "不显示"
                CoverPosition.RIGHT -> "右侧"
            },
            selectedValue = cfg.cover,
            options = listOf(
                DropdownOption("左侧", CoverPosition.LEFT),
                DropdownOption("不显示", CoverPosition.NONE),
                DropdownOption("右侧", CoverPosition.RIGHT),
            ),
            onSelected = { onUpdate(cfg.copy(cover = it)) },
        )
        if (cfg.cover != CoverPosition.NONE) {
            DropdownPreference(
                title = "封面样式",
                summary = when (cfg.coverShape) {
                    CoverShape.SQUARE -> "方形"
                    CoverShape.CIRCLE -> "圆形（静态）"
                    CoverShape.CIRCLE_ROTATE -> "圆形（转动）"
                },
                selectedValue = cfg.coverShape,
                options = listOf(
                    DropdownOption("方形", CoverShape.SQUARE),
                    DropdownOption("圆形（静态）", CoverShape.CIRCLE),
                    DropdownOption("圆形（转动）", CoverShape.CIRCLE_ROTATE),
                ),
                onSelected = { onUpdate(cfg.copy(coverShape = it)) },
            )
        }
        DropdownPreference(
            title = "背屏刷新率",
            summary = frameRateLabel(cfg.frameRate),
            selectedValue = cfg.frameRate,
            options = listOf(
                DropdownOption("120 帧", LyricFrameRate.FPS_120),
                DropdownOption("60 帧（省电）", LyricFrameRate.FPS_60),
            ),
            onSelected = { onUpdate(cfg.copy(frameRate = it)) },
        )
        SwitchPreference(
            title = "封面取色背景",
            summary = "从封面提取颜色作为背屏背景",
            checked = cfg.dynamicBackground,
            onCheckedChange = { onUpdate(cfg.copy(dynamicBackground = it)) },
        )
        DropdownPreference(
            title = "文字配色",
            summary = when (cfg.textColorMode) {
                TextColorMode.DEFAULT -> "默认白色"
                TextColorMode.COVER -> "提取封面颜色"
                TextColorMode.COVER_GRADIENT -> "提取封面渐变色"
            },
            selectedValue = cfg.textColorMode,
            options = listOf(
                DropdownOption("默认白色", TextColorMode.DEFAULT),
                DropdownOption("提取封面颜色", TextColorMode.COVER),
                DropdownOption("提取封面渐变色", TextColorMode.COVER_GRADIENT),
            ),
            onSelected = { onUpdate(cfg.copy(textColorMode = it)) },
        )
        if (lyricSource == LyricSource.LYRICON) {
            SwitchPreference(
                title = "显示副歌词",
                summary = "显示双声部/翻译/罗马音副行",
                checked = cfg.showSecondary,
                onCheckedChange = { onUpdate(cfg.copy(showSecondary = it)) },
            )
            if (cfg.showSecondary) {
                SwitchPreference(
                    title = "显示翻译",
                    summary = "有翻译时作为副行显示",
                    checked = cfg.showTranslation,
                    onCheckedChange = { onUpdate(cfg.copy(showTranslation = it)) },
                )
                SwitchPreference(
                    title = "显示罗马音",
                    summary = "有罗马音时作为副行显示",
                    checked = cfg.showRoma,
                    onCheckedChange = { onUpdate(cfg.copy(showRoma = it)) },
                )
            }
        }
    }
}

@Composable
private fun ConfigTextCard(
    cfg: RearConfig,
    onUpdate: (RearConfig) -> Unit,
    onEditInt: (title: String, value: Int, suffix: String, allowNegative: Boolean, onConfirm: (Int) -> Unit) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = "粗体",
            summary = "使用更醒目的字重",
            checked = cfg.bold,
            onCheckedChange = { onUpdate(cfg.copy(bold = it)) },
        )
        SwitchPreference(
            title = "斜体",
            summary = "为歌词应用斜体样式",
            checked = cfg.italic,
            onCheckedChange = { onUpdate(cfg.copy(italic = it)) },
        )
        ArrowPreference(
            title = "字重",
            summary = cfg.fontWeight.toString(),
            onClick = { onEditInt("字重", cfg.fontWeight, "", false) { onUpdate(cfg.copy(fontWeight = it)) } },
        )
    }
}

@Composable
private fun ConfigProgressCard(
    cfg: RearConfig,
    lyricSource: LyricSource,
    onUpdate: (RearConfig) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = "相对进度",
            summary = "当前词/字内按时间平滑扫过（关闭则到点整体点亮）",
            checked = cfg.relativeProgress,
            onCheckedChange = { onUpdate(cfg.copy(relativeProgress = it)) },
        )
        if (cfg.relativeProgress) {
            SwitchPreference(
                title = "渐变高亮",
                summary = "高亮扫过的前沿用渐变软边而非硬切",
                checked = cfg.gradientProgress,
                onCheckedChange = { onUpdate(cfg.copy(gradientProgress = it)) },
            )
        }
        if (lyricSource == LyricSource.LYRICON) {
            SwitchPreference(
                title = "相对高亮",
                summary = "汉字逐字点亮（关闭则整词为单位，较粗）",
                checked = cfg.relativeHighlight,
                onCheckedChange = { onUpdate(cfg.copy(relativeHighlight = it)) },
            )
        }
        SwitchPreference(
            title = "模拟逐字",
            summary = "无逐字时间的歌曲按时长假装逐字；关闭则整句整句切",
            checked = cfg.simulateWordTiming,
            onCheckedChange = { onUpdate(cfg.copy(simulateWordTiming = it)) },
        )
        if (lyricSource == LyricSource.LYRICON) {
            SwitchPreference(
                title = "逐字抬起",
                summary = "未唱文字下沉、唱到时抬起回正；关闭则未唱/已唱文字保持同一水平线",
                checked = cfg.sinkAnimation,
                onCheckedChange = { onUpdate(cfg.copy(sinkAnimation = it)) },
            )
        }
    }
}

/**
 * 安全区与微调：安全区/左右偏移是步进整数（默认 0、可负）；歌词字号(px)/小锁半径(dp)/细线长度(dp)
 * 是绝对值，默认即当前实际大小。歌词字号默认值取背屏当前自动适配的实际 px（[LyricRenderMetrics]）。
 */
@Composable
private fun ConfigAdjustCard(
    cfg: RearConfig,
    lyricSource: LyricSource,
    onUpdate: (RearConfig) -> Unit,
    onEditInt: (title: String, value: Int, suffix: String, allowNegative: Boolean, onConfirm: (Int) -> Unit) -> Unit,
) {
    val autoTextPx by LyricRenderMetrics.autoTextSizePx.collectAsState()
    val currentTextPx = if (cfg.lyricTextSize > 0) cfg.lyricTextSize else autoTextPx
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = "左安全区",
            summary = cfg.safeAreaLeft.toString(),
            onClick = { onEditInt("左安全区", cfg.safeAreaLeft, "步", true) { onUpdate(cfg.copy(safeAreaLeft = it)) } },
        )
        ArrowPreference(
            title = "右安全区",
            summary = cfg.safeAreaRight.toString(),
            onClick = { onEditInt("右安全区", cfg.safeAreaRight, "步", true) { onUpdate(cfg.copy(safeAreaRight = it)) } },
        )
        // 字号、锁定与拖动时间轴只存在于词幕的完整歌词交互页；SuperLyric 单句页不消费这些参数。
        if (lyricSource == LyricSource.LYRICON) {
            ArrowPreference(
                title = "歌词文字大小",
                summary = if (currentTextPx > 0) "$currentTextPx px" else "自动",
                onClick = { onEditInt("歌词文字大小", currentTextPx, "px", false) { onUpdate(cfg.copy(lyricTextSize = it)) } },
            )
            ArrowPreference(
                title = "解锁小锁半径",
                summary = "${cfg.lockSize} dp",
                onClick = { onEditInt("解锁小锁半径", cfg.lockSize, "dp", false) { onUpdate(cfg.copy(lockSize = it)) } },
            )
            ArrowPreference(
                title = "解锁小锁左右",
                summary = cfg.lockOffset.toString(),
                onClick = { onEditInt("解锁小锁左右", cfg.lockOffset, "步", true) { onUpdate(cfg.copy(lockOffset = it)) } },
            )
            ArrowPreference(
                title = "拖动时间左右",
                summary = cfg.timeOffset.toString(),
                onClick = { onEditInt("拖动时间左右", cfg.timeOffset, "步", true) { onUpdate(cfg.copy(timeOffset = it)) } },
            )
            ArrowPreference(
                title = "时间细线长度",
                summary = "${cfg.timeLineLength} dp",
                onClick = { onEditInt("时间细线长度", cfg.timeLineLength, "dp", false) { onUpdate(cfg.copy(timeLineLength = it)) } },
            )
        }
    }
}

private sealed interface ConfigDialogState {
    data class IntInput(
        val title: String,
        val value: Int,
        val suffix: String,
        val allowNegative: Boolean,
        val onConfirm: (Int) -> Unit,
    ) : ConfigDialogState
}

@Composable
private fun ConfigDialogHost(
    state: ConfigDialogState?,
    onDismiss: () -> Unit,
) {
    // state 置空后仍保留最后一个对话框直到退场动画结束（onDismissFinished 清掉）。
    var rendered by remember { mutableStateOf<ConfigDialogState?>(null) }
    if (state != null && state !== rendered) rendered = state
    val current = rendered ?: return
    val show = state != null

    when (current) {
        is ConfigDialogState.IntInput -> NumberInputDialog(
            show = show,
            title = current.title,
            initialValue = current.value.toString(),
            suffix = current.suffix,
            allowNegative = current.allowNegative,
            allowDecimal = false,
            onDismiss = onDismiss,
            onDismissFinished = { rendered = null },
            onConfirm = {
                it.toIntOrNull()?.let(current.onConfirm)
                onDismiss()
            },
        )
    }
}

@Composable
private fun NumberInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    suffix: String,
    allowNegative: Boolean,
    allowDecimal: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    val normalized = value.trim()
    val valid = normalized.toDoubleOrNull() != null

    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        NumberTextField(
            value = value,
            onValueChange = { value = it },
            label = if (suffix.isBlank()) "输入数值" else "输入数值（$suffix）",
            allowDecimal = allowDecimal,
            allowNegative = allowNegative,
            autoSelectOnFocus = true,
            isError = !valid,
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(normalized) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = valid,
            )
        }
    }
}
