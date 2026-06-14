package com.zhitool.rearlyric.ui.screen

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhitool.rearlyric.lyric.AppFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

data class AppInfo(val label: String, val pkg: String)

enum class AppPickerMode { FILTER, PACKAGE_STYLE }

@Composable
fun AppPickerScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    mode: AppPickerMode,
    filter: AppFilter? = null,
    onFilterToggle: ((String, Boolean) -> Unit)? = null,
    onAppPicked: ((AppInfo) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val apps by produceState(emptyList<AppInfo>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "选择应用",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
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
            if (apps.isEmpty()) {
                item {
                    Text(
                        "加载应用列表…",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(apps, key = { it.pkg }) { app ->
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                    when (mode) {
                        AppPickerMode.FILTER -> {
                            val currentFilter = filter ?: AppFilter()
                            SwitchPreference(
                                title = app.label,
                                summary = app.pkg,
                                checked = app.pkg in currentFilter.selectedApps,
                                onCheckedChange = { checked ->
                                    onFilterToggle?.invoke(app.pkg, checked)
                                },
                            )
                        }

                        AppPickerMode.PACKAGE_STYLE -> {
                            ArrowPreference(
                                title = app.label,
                                summary = "点击创建或编辑该应用的单独配置",
                                onClick = {
                                    onAppPicked?.invoke(app)
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .filter { it.pkg != context.packageName }
            .sortedBy { it.label }
    }.getOrDefault(emptyList())
}
