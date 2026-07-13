/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.core.RearTools
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.lyric.HookSettings
import com.zhitool.rearlyric.tools.RearToolsService
import com.zhitool.rearlyric.tools.ToolsConfigState
import com.zhitool.rearlyric.tools.ToolsConfigStore
import com.zhitool.rearlyric.tools.notify.NotifyConfigState
import com.zhitool.rearlyric.tools.notify.NotifyConfigStore
import com.zhitool.rearlyric.tools.record.ScreenRecordService
import com.zhitool.rearlyric.ui.components.DropdownOption
import com.zhitool.rearlyric.ui.components.DropdownPreference
import com.zhitool.rearlyric.ui.components.NumberTextField
import com.zhitool.rearlyric.ui.components.OverlayDialog
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ToolsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val tools by ToolsConfigState.flow.collectAsState()
    val notify by NotifyConfigState.flow.collectAsState()
    val hookState by HookSettings.flow.collectAsState()
    val recOverlay by ScreenRecordService.shownFlow.collectAsState()

    var showNotifyPicker by remember { mutableStateOf(false) }
    if (showNotifyPicker) {
        AppPickerScreen(
            contentPadding = contentPadding,
            onBack = { showNotifyPicker = false },
            mode = AppPickerMode.SELECT,
            selectedApps = notify.selectedApps,
            onSelectToggle = { pkg, checked ->
                val next = if (checked) notify.selectedApps + pkg else notify.selectedApps - pkg
                NotifyConfigStore.save(context, notify.copy(selectedApps = next))
            },
        )
        return
    }

    // 背屏 DPI / 旋转当前值（root 异步读取）。
    var dpi by remember { mutableStateOf<Int?>(null) }
    var rotation by remember { mutableStateOf<Int?>(null) }
    fun reload() = thread(name = "zhi-tools-read") {
        if (RootShell.available || RootShell.refresh()) {
            val d = RearTools.getRearDpi()
            val r = RearTools.getRearRotation()
            ContextCompat.getMainExecutor(context).execute {
                dpi = d.takeIf { it > 0 }
                rotation = r
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    var showDpiDialog by remember { mutableStateOf(false) }
    var showUriDialog by remember { mutableStateOf(false) }
    var showAutoSecDialog by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = { TopAppBar(title = "工具", scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            // ── 投屏控制 ──
            item { SmallTitle("投屏控制") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "切换当前应用到背屏",
                        summary = "把主屏前台应用投到背屏，并保活",
                        onClick = {
                            runTool(context, "正在切换…") {
                                val app = RearTools.switchCurrentAppToRear()
                                RearTools.collapseStatusBar()
                                if (app != null) {
                                    RearToolsService.startKeeper(context, app)
                                    "${label(context, app.packageName)} 已投到背屏"
                                } else "切换失败：未找到前台应用"
                            }
                        },
                    )
                    ArrowPreference(
                        title = "返回主屏",
                        summary = "把背屏当前应用拉回主屏",
                        onClick = {
                            runTool(context, "正在返回…") {
                                val app = RearTools.returnRearToMain()
                                RearToolsService.stopKeeper(context)
                                if (app != null) "${label(context, app.packageName)} 已返回主屏" else "背屏没有应用"
                            }
                        },
                    )
                    ArrowPreference(
                        title = "背屏截图",
                        summary = "截取背屏画面保存到相册",
                        onClick = {
                            runTool(context, "正在截图…") {
                                if (RearTools.takeRearScreenshot()) "背屏截图已保存到相册" else "截图失败"
                            }
                        },
                    )
                }
            }

            // ── 显示调整 ──
            item { SmallTitle("显示调整") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "背屏 DPI",
                        summary = dpi?.let { "当前 $it（推荐 260–350）" } ?: "点击设置（推荐 260–350）",
                        onClick = { showDpiDialog = true },
                    )
                    ArrowPreference(
                        title = "还原默认 DPI",
                        summary = "恢复背屏默认显示密度",
                        onClick = {
                            runTool(context, "正在还原…") {
                                val ok = RearTools.resetRearDpi()
                                reload()
                                if (ok) "背屏 DPI 已还原" else "还原失败"
                            }
                        },
                    )
                    DropdownPreference(
                        title = "背屏旋转",
                        summary = rotation?.let { rotationLabel(it) } ?: "0° / 90° / 180° / 270°",
                        selectedValue = rotation ?: 0,
                        options = (0..3).map { DropdownOption(rotationLabel(it), it) },
                        onSelected = { selectedRotation ->
                            runTool(context, "正在旋转…") {
                                val ok = RearTools.setRearRotation(selectedRotation)
                                reload()
                                if (ok) {
                                    "背屏已旋转至 ${rotationLabel(selectedRotation)}"
                                } else {
                                    "旋转失败"
                                }
                            }
                        },
                    )
                }
            }

            // ── 背屏录屏 ──
            item { SmallTitle("录屏") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "背屏录屏",
                        summary = "背屏录屏胶囊悬浮窗",
                        checked = recOverlay,
                        onCheckedChange = { on ->
                            if (on) startRecordOverlay(context) else stopRecordOverlay(context)
                        },
                    )
                }
            }

            // ── 充电动画 ──
            item { SmallTitle("充电动画") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "充电动画",
                        summary = "插电时背屏显示上升的电量液体动画",
                        checked = tools.chargeAnimation,
                        onCheckedChange = {
                            ToolsConfigStore.save(context, tools.copy(chargeAnimation = it))
                            RearToolsService.syncFromConfig(context)
                        },
                    )
                    if (tools.chargeAnimation) {
                        SwitchPreference(
                            title = "充电动画常亮",
                            summary = "充电期间一直显示（否则约 8 秒后消失）",
                            checked = tools.chargeAlwaysOn,
                            onCheckedChange = { ToolsConfigStore.save(context, tools.copy(chargeAlwaysOn = it)) },
                        )
                    }
                }
            }

            // ── 背屏通知 ──
            item { SmallTitle("背屏通知") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "背屏通知推送",
                        summary = "通过 LSPosed 获取选中应用通知，无需通知使用权",
                        checked = notify.enabled,
                        onCheckedChange = { NotifyConfigStore.save(context, notify.copy(enabled = it)) },
                    )
                    if (notify.enabled) {
                        ArrowPreference(
                            title = "选择应用",
                            summary = "已选 ${notify.selectedApps.size} 个",
                            onClick = { showNotifyPicker = true },
                        )
                        SwitchPreference(
                            title = "隐藏标题",
                            summary = "隐私模式：不显示通知标题",
                            checked = notify.hideTitle,
                            onCheckedChange = { NotifyConfigStore.save(context, notify.copy(hideTitle = it)) },
                        )
                        SwitchPreference(
                            title = "隐藏内容",
                            summary = "隐私模式：不显示通知内容",
                            checked = notify.hideContent,
                            onCheckedChange = { NotifyConfigStore.save(context, notify.copy(hideContent = it)) },
                        )
                        SwitchPreference(
                            title = "跟随系统勿扰",
                            summary = "勿扰模式开启时不在背屏显示",
                            checked = notify.followDnd,
                            onCheckedChange = { NotifyConfigStore.save(context, notify.copy(followDnd = it)) },
                        )
                        SwitchPreference(
                            title = "仅倒扣手机时",
                            summary = "仅当主屏被遮盖（手机倒扣）时推送",
                            checked = notify.onlyWhenUpsideDown,
                            onCheckedChange = { NotifyConfigStore.save(context, notify.copy(onlyWhenUpsideDown = it)) },
                        )
                        ArrowPreference(
                            title = "自动消失时间",
                            summary = "${notify.autoDestroySeconds} 秒",
                            onClick = { showAutoSecDialog = true },
                        )
                    }
                }
            }

            // ── 保活与常亮 ──
            item { SmallTitle("保活与常亮") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "背屏常亮",
                        summary = "投放应用到背屏期间保持背屏常亮",
                        checked = tools.keepScreenOn,
                        onCheckedChange = {
                            ToolsConfigStore.save(context, tools.copy(keepScreenOn = it))
                            RearToolsService.syncFromConfig(context)
                        },
                    )
                    SwitchPreference(
                        title = "未投放时常亮",
                        summary = "未投放也保持背屏常亮（警告：可能烧屏和额外耗电）",
                        checked = tools.alwaysWakeUp,
                        onCheckedChange = {
                            ToolsConfigStore.save(context, tools.copy(alwaysWakeUp = it))
                            RearToolsService.syncFromConfig(context)
                        },
                    )
                    SwitchPreference(
                        title = "背屏遮盖检测",
                        summary = "用手遮住背屏（接近传感器）即把投放的应用拉回主屏",
                        checked = tools.coverDetection,
                        onCheckedChange = {
                            ToolsConfigStore.save(context, tools.copy(coverDetection = it))
                            RearToolsService.syncFromConfig(context)
                        },
                    )
                }
            }

            // ── 背屏保活（系统 Hook，从配置页迁来） ──
            item { SmallTitle("系统级背屏保活（需 LSPosed，修改后重启生效）") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "允许 ZHITool 在背屏启动",
                        summary = "加入系统背屏 Activity 白名单，锁屏状态也允许启动",
                        checked = hookState.allowRearActivity,
                        onCheckedChange = {
                            HookSettings.save(context, hookState.copy(allowRearActivity = it))
                        },
                    )
                    SwitchPreference(
                        title = "锁屏过渡不返回桌面",
                        summary = "拦截系统锁屏过渡的返回桌面动作（全局影响，默认关闭）",
                        checked = hookState.skipLockBackHome,
                        onCheckedChange = {
                            HookSettings.save(context, hookState.copy(skipLockBackHome = it))
                        },
                    )
                    SwitchPreference(
                        title = "锁屏后保持当前背屏页面",
                        summary = "AOD/息屏时仅阻止 ZHITool 被切回背屏桌面",
                        checked = hookState.guardSubScreenHome,
                        onCheckedChange = {
                            HookSettings.save(context, hookState.copy(guardSubScreenHome = it))
                        },
                    )
                    SwitchPreference(
                        title = "禁用 ZHITool 双击息屏",
                        summary = "ZHITool 位于背屏前台时，屏蔽系统双击息屏手势",
                        checked = hookState.disableDoubleTapSleep,
                        onCheckedChange = {
                            HookSettings.save(context, hookState.copy(disableDoubleTapSleep = it))
                        },
                    )
                    SwitchPreference(
                        title = "后台白名单与进程锁定",
                        summary = "注入系统动态白名单并锁定应用，降低后台被清理概率",
                        checked = hookState.keepBackground,
                        onCheckedChange = {
                            HookSettings.save(context, hookState.copy(keepBackground = it))
                        },
                    )
                    SwitchPreference(
                        title = "禁用背屏保护",
                        summary = "阻止系统显示背屏保护层（全局影响，默认关闭）",
                        checked = hookState.disableRearScreenCover,
                        onCheckedChange = {
                            HookSettings.save(context, hookState.copy(disableRearScreenCover = it))
                        },
                    )
                }
            }

            // ── 高级 ──
            item { SmallTitle("高级") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "URI 调用",
                        summary = "支持 zhitool:// 与 mrss://（Tasker / MacroDroid 等）",
                        onClick = { showUriDialog = true },
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showDpiDialog) {
        NumberDialog(
            title = "设置背屏 DPI",
            initial = (dpi ?: 300).toString(),
            label = "DPI（推荐 260–350）",
            onDismiss = { showDpiDialog = false },
            onConfirm = { v ->
                showDpiDialog = false
                v.toIntOrNull()?.let { value ->
                    runTool(context, "正在设置…") {
                        val ok = RearTools.setRearDpi(value)
                        reload()
                        if (ok) "背屏 DPI 已设为 $value" else "设置失败"
                    }
                }
            },
        )
    }

    if (showAutoSecDialog) {
        NumberDialog(
            title = "自动消失时间（秒）",
            initial = notify.autoDestroySeconds.toString(),
            label = "秒（默认 5）",
            onDismiss = { showAutoSecDialog = false },
            onConfirm = { v ->
                showAutoSecDialog = false
                v.toIntOrNull()?.let { NotifyConfigStore.save(context, notify.copy(autoDestroySeconds = it.coerceIn(1, 3600))) }
            },
        )
    }

    if (showUriDialog) {
        OverlayDialog(
            show = true,
            title = "URI 调用",
            onDismissRequest = { showUriDialog = false },
        ) {
            Column {
                listOf(
                    "zhitool://switch?current=1  切换当前应用到背屏",
                    "zhitool://switch?packageName=<包名>  投放指定应用",
                    "zhitool://return?current=1  返回主屏",
                    "zhitool://screenshot  背屏截图",
                    "zhitool://config?dpi=300&rotation=0  设置 DPI/旋转",
                    "（mrss:// 同样支持，兼容旧脚本）",
                ).forEach {
                    Text(
                        text = it,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                TextButton(
                    text = "好",
                    onClick = { showUriDialog = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun NumberDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.trim().toIntOrNull() != null
    OverlayDialog(show = true, title = title, onDismissRequest = onDismiss) {
        NumberTextField(
            value = value,
            onValueChange = { value = it },
            label = label,
            allowDecimal = false,
            allowNegative = false,
            autoSelectOnFocus = true,
            isError = !valid,
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(value.trim()) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = valid,
            )
        }
    }
}

private fun startRecordOverlay(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return
    }
    ContextCompat.startForegroundService(
        context,
        Intent(context, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_SHOW),
    )
}

private fun stopRecordOverlay(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP),
    )
}

private fun rotationLabel(r: Int): String = when (r) {
    1 -> "90°"
    2 -> "180°"
    3 -> "270°"
    else -> "0°"
}

private fun label(context: Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/**
 * 在后台线程跑一个 root 工具动作，先弹「进行中」Toast，结束后弹结果 Toast。
 * [block] 返回结果文案；返回 null 表示不弹结果。
 */
private fun runTool(context: Context, pending: String?, block: () -> String?) {
    val appCtx = context.applicationContext
    if (pending != null) Toast.makeText(appCtx, pending, Toast.LENGTH_SHORT).show()
    thread(name = "zhi-tool-action") {
        if (!RootShell.available && !RootShell.refresh()) {
            ContextCompat.getMainExecutor(appCtx).execute {
                Toast.makeText(appCtx, "请先授权 Root", Toast.LENGTH_SHORT).show()
            }
            return@thread
        }
        val result = runCatching { block() }.getOrElse { "操作出错：${it.message}" }
        if (result != null) {
            ContextCompat.getMainExecutor(appCtx).execute {
                Toast.makeText(appCtx, result, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
