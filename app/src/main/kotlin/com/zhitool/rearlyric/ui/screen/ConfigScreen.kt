package com.zhitool.rearlyric.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ConfigScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val baseConfig by RearConfigState.flow.collectAsState()
    val filter by AppFilterState.flow.collectAsState()
    val packageStyles by PackageStyleState.flow.collectAsState()
    val lyricSource by LyricSourceState.flow.collectAsState()

    var showFilterPicker by rememberSaveable { mutableStateOf(false) }
    var showPackagePicker by rememberSaveable { mutableStateOf(false) }
    var editingPackage by remember { mutableStateOf<PackageStyle?>(null) }

    if (editingPackage != null) {
        PackageStyleConfigScreen(
            contentPadding = contentPadding,
            packageStyle = editingPackage!!,
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
            item {
                RearConfigEditorContent(
                    title = "基础配置",
                    cfg = baseConfig,
                    onUpdate = { ConfigStore.save(context, it) },
                )
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
                    ArrowPreference(
                        title = "选择监听应用",
                        summary = "已选 ${filter.selectedApps.size} 个",
                        onClick = { showFilterPicker = true },
                    )
                    ArrowPreference(
                        title = "添加单独配置",
                        summary = "为某个播放器单独设置歌词与封面样式",
                        onClick = { showPackagePicker = true },
                    )
                }
            }

            if (packageStyles.isNotEmpty()) {
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

            item { SmallTitle("歌词动画") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val superLyric = lyricSource == LyricSource.SUPERLYRIC
                    ArrowPreference(
                        title = "歌词动画",
                        summary = if (!superLyric) {
                            "仅 SuperLyric 数据源可用"
                        } else when (baseConfig.lyricAnimation) {
                            LyricAnimation.NONE -> "无"
                            LyricAnimation.RANDOM_RISE -> "随机升起"
                        },
                        onClick = {
                            if (superLyric) {
                                val next = if (baseConfig.lyricAnimation == LyricAnimation.RANDOM_RISE) {
                                    LyricAnimation.NONE
                                } else {
                                    LyricAnimation.RANDOM_RISE
                                }
                                ConfigStore.save(context, baseConfig.copy(lyricAnimation = next))
                            }
                        },
                    )
                }
            }

            item { SmallTitle("封面与来源") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "歌词数据源",
                        summary = when (lyricSource) {
                            LyricSource.LYRICON -> "词幕（lyricon 生态）"
                            LyricSource.SUPERLYRIC -> "SuperLyric（实时逐句）"
                        },
                        onClick = {
                            val next = if (lyricSource == LyricSource.LYRICON) {
                                LyricSource.SUPERLYRIC
                            } else {
                                LyricSource.LYRICON
                            }
                            ConfigStore.saveLyricSource(context, next)
                        },
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
}

@Composable
private fun PackageStyleConfigScreen(
    contentPadding: PaddingValues,
    packageStyle: PackageStyle,
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
    onUpdate: (RearConfig) -> Unit,
) {
    var dialogState by remember { mutableStateOf<ConfigDialogState?>(null) }

    SmallTitle(title)
    ConfigBasicCard(
        cfg = cfg,
        onUpdate = onUpdate,
        onChoose = { t, current, options, confirm ->
            dialogState = ConfigDialogState.Choice(
                title = t,
                selectedValue = current,
                options = options,
                onConfirm = confirm,
            )
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
        onUpdate = onUpdate,
    )
    SmallTitle("电池与充电")
    ConfigBatteryCard(
        cfg = cfg,
        onUpdate = onUpdate,
        onChoose = { t, current, options, confirm ->
            dialogState = ConfigDialogState.Choice(
                title = t,
                selectedValue = current,
                options = options,
                onConfirm = confirm,
            )
        },
    )
    SmallTitle("安全区与微调")
    ConfigAdjustCard(
        cfg = cfg,
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

@Composable
private fun ConfigBatteryCard(
    cfg: RearConfig,
    onUpdate: (RearConfig) -> Unit,
    onChoose: (title: String, current: String, options: List<ChoiceItem>, onConfirm: (String) -> Unit) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = "电池",
            summary = when (cfg.batteryMode) {
                BatteryMode.NONE -> "不显示"
                BatteryMode.WHEN_CHARGING -> "充电时显示"
                BatteryMode.ALWAYS -> "一直显示"
            },
            onClick = {
                onChoose(
                    "电池",
                    cfg.batteryMode.name,
                    listOf(
                        ChoiceItem("不显示", BatteryMode.NONE.name),
                        ChoiceItem("充电时显示", BatteryMode.WHEN_CHARGING.name),
                        ChoiceItem("一直显示", BatteryMode.ALWAYS.name),
                    )
                ) { onUpdate(cfg.copy(batteryMode = BatteryMode.valueOf(it))) }
            },
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
    onUpdate: (RearConfig) -> Unit,
    onChoose: (title: String, current: String, options: List<ChoiceItem>, onConfirm: (String) -> Unit) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = "封面位置",
            summary = when (cfg.cover) {
                CoverPosition.LEFT -> "左侧"
                CoverPosition.NONE -> "不显示"
                CoverPosition.RIGHT -> "右侧"
            },
            onClick = {
                onChoose(
                    "封面位置",
                    cfg.cover.name,
                    listOf(
                        ChoiceItem("左侧", CoverPosition.LEFT.name),
                        ChoiceItem("不显示", CoverPosition.NONE.name),
                        ChoiceItem("右侧", CoverPosition.RIGHT.name),
                    )
                ) { onUpdate(cfg.copy(cover = CoverPosition.valueOf(it))) }
            },
        )
        ArrowPreference(
            title = "封面样式",
            summary = when (cfg.coverShape) {
                CoverShape.SQUARE -> "方形"
                CoverShape.CIRCLE -> "圆形（静态）"
                CoverShape.CIRCLE_ROTATE -> "圆形（转动）"
            },
            onClick = {
                onChoose(
                    "封面样式",
                    cfg.coverShape.name,
                    listOf(
                        ChoiceItem("方形", CoverShape.SQUARE.name),
                        ChoiceItem("圆形（静态）", CoverShape.CIRCLE.name),
                        ChoiceItem("圆形（转动）", CoverShape.CIRCLE_ROTATE.name),
                    )
                ) { onUpdate(cfg.copy(coverShape = CoverShape.valueOf(it))) }
            },
        )
        ArrowPreference(
            title = "背屏刷新率",
            summary = when (cfg.frameRate) {
                LyricFrameRate.FPS_120 -> "120 帧"
                LyricFrameRate.FPS_60 -> "60 帧（省电）"
            },
            onClick = {
                onChoose(
                    "背屏刷新率",
                    cfg.frameRate.name,
                    listOf(
                        ChoiceItem("120 帧", LyricFrameRate.FPS_120.name),
                        ChoiceItem("60 帧（省电）", LyricFrameRate.FPS_60.name),
                    )
                ) { onUpdate(cfg.copy(frameRate = LyricFrameRate.valueOf(it))) }
            },
        )
        SwitchPreference(
            title = "封面取色背景",
            summary = "从封面提取颜色作为背屏背景",
            checked = cfg.dynamicBackground,
            onCheckedChange = { onUpdate(cfg.copy(dynamicBackground = it)) },
        )
        ArrowPreference(
            title = "文字配色",
            summary = when (cfg.textColorMode) {
                TextColorMode.DEFAULT -> "默认白色"
                TextColorMode.COVER -> "提取封面颜色"
                TextColorMode.COVER_GRADIENT -> "提取封面渐变色"
            },
            onClick = {
                onChoose(
                    "文字配色",
                    cfg.textColorMode.name,
                    listOf(
                        ChoiceItem("默认白色", TextColorMode.DEFAULT.name),
                        ChoiceItem("提取封面颜色", TextColorMode.COVER.name),
                        ChoiceItem("提取封面渐变色", TextColorMode.COVER_GRADIENT.name),
                    )
                ) { onUpdate(cfg.copy(textColorMode = TextColorMode.valueOf(it))) }
            },
        )
        SwitchPreference(
            title = "显示副歌词",
            summary = "显示双声部/翻译/罗马音副行",
            checked = cfg.showSecondary,
            onCheckedChange = { onUpdate(cfg.copy(showSecondary = it)) },
        )
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
    onUpdate: (RearConfig) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = "渐变高亮",
            summary = "高亮扫过的前沿用渐变软边而非硬切",
            checked = cfg.gradientProgress,
            onCheckedChange = { onUpdate(cfg.copy(gradientProgress = it)) },
        )
        SwitchPreference(
            title = "相对进度",
            summary = "当前词/字内按时间平滑扫过（关闭则到点整体点亮）",
            checked = cfg.relativeProgress,
            onCheckedChange = { onUpdate(cfg.copy(relativeProgress = it)) },
        )
        SwitchPreference(
            title = "相对高亮",
            summary = "汉字逐字点亮（关闭则整词为单位，较粗）",
            checked = cfg.relativeHighlight,
            onCheckedChange = { onUpdate(cfg.copy(relativeHighlight = it)) },
        )
        SwitchPreference(
            title = "模拟逐字",
            summary = "无逐字时间的歌曲按时长假装逐字；关闭则整句整句切",
            checked = cfg.simulateWordTiming,
            onCheckedChange = { onUpdate(cfg.copy(simulateWordTiming = it)) },
        )
    }
}

/**
 * 安全区与微调：安全区/左右偏移是步进整数（默认 0、可负）；歌词字号(px)/小锁半径(dp)/细线长度(dp)
 * 是绝对值，默认即当前实际大小。歌词字号默认值取背屏当前自动适配的实际 px（[LyricRenderMetrics]）。
 */
@Composable
private fun ConfigAdjustCard(
    cfg: RearConfig,
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

private data class ChoiceItem(val label: String, val value: String)

private sealed interface ConfigDialogState {
    data class IntInput(
        val title: String,
        val value: Int,
        val suffix: String,
        val allowNegative: Boolean,
        val onConfirm: (Int) -> Unit,
    ) : ConfigDialogState

    data class Choice(
        val title: String,
        val selectedValue: String,
        val options: List<ChoiceItem>,
        val onConfirm: (String) -> Unit,
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
        is ConfigDialogState.Choice -> ChoiceDialog(
            show = show,
            title = current.title,
            selectedValue = current.selectedValue,
            options = current.options,
            onDismiss = onDismiss,
            onDismissFinished = { rendered = null },
            onSelect = {
                current.onConfirm(it)
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

@Composable
private fun ChoiceDialog(
    show: Boolean,
    title: String,
    selectedValue: String,
    options: List<ChoiceItem>,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onSelect: (String) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column {
            options.forEach { item ->
                val selected = item.value == selectedValue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(miuixShape(16.dp))
                        .clickable { onSelect(item.value) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        modifier = Modifier.weight(1f),
                        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (selected) {
                        Text(
                            text = "✓",
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.padding(top = 12.dp)) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
