package com.zhitool.rearlyric.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.R
import com.zhitool.rearlyric.update.UpdateChecker
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 鸣谢项目（参考/借鉴的开源项目，可点击跳转）。 */
private data class CreditProject(val name: String, val role: String, val url: String)

private val CREDIT_PROJECTS = listOf(
    CreditProject("REAREye", "UI 设计 / 背屏保活", "https://github.com/killerprojecte/REAREye"),
    CreditProject("词幕 Lyricon", "歌词渲染引擎", "https://github.com/tomakino/lyricon"),
    CreditProject("MRSS", "背屏投放方案", "https://github.com/GoldenglowSusie"),
    CreditProject("LyricProvider", "歌词数据来源", "https://github.com/tomakino/LyricProvider"),
)

/** 开源引用（使用到的第三方库）。url 为空则不可跳转，仅展示。 */
private data class OpenSourceRef(val name: String, val usage: String, val url: String = "")

private val OPEN_SOURCE_REFS = listOf(
    OpenSourceRef("Jetpack Compose / AndroidX", "UI 框架", "https://github.com/androidx/androidx"),
    OpenSourceRef("miuix", "MIUI 风格组件", "https://github.com/miuix-kotlin-multiplatform/miuix"),
    OpenSourceRef("kyant backdrop / capsule", "液态玻璃模糊", "https://github.com/Kyant0"),
    OpenSourceRef("LuckyPray DexKit", "混淆方法 / 字段定位", "https://github.com/LuckyPray/DexKit"),
    OpenSourceRef("libxposed", "Xposed 模块 API", "https://github.com/libxposed"),
    OpenSourceRef("AndroidViewAnimations", "歌词进出场动画", "https://github.com/daimajia/AndroidViewAnimations"),
    OpenSourceRef("kotlinx.coroutines", "协程", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceRef("AndroidX Palette", "封面取色"),
)

// 链接入口。
private const val URL_GITHUB = "https://github.com/XZOWO/ZHITool"
private const val URL_DOCS = "https://github.com/XZOWO/ZHITool"            // 使用文档 = 项目主页
private const val URL_AFDIAN = "https://www.ifdian.net/a/XZOWO"
private const val URL_QQ_GROUP = "https://qun.qq.com/universal-share/share?ac=1&authKey=XF%2BPdk7snLnIgNXJcJ%2Fk%2BgnnnhqkaZMGQj7zTMsbs0t%2BaIxusC7x9%2BzROdVGelJW&busi_data=eyJncm91cENvZGUiOiIyMjg1Njc3NDIiLCJ0b2tlbiI6IkdKK0xGZHRXbnhkc1VrNFJNdERLQWttWG5qbFNONmhicSsrRXNRNTM1d1VOTXZYL29Hejd1WHh3ZUdVbGx2cSsiLCJ1aW4iOiIyNjgxODA5ODA1In0%3D&data=ivy2g_RE6L62kuuQqxNI6oE1DuBEAFES2wj_7aR59T55mKrCSDw7nYPPmgOtS1AUOz9rSkqzBW7hAdv2WyTsaQ&svctype=4&tempid=h5_group_info"
private const val URL_COOLAPK = "https://www.coolapk.com/u/12965336"

@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    var showCredits by rememberSaveable { mutableStateOf(false) }
    if (showCredits) {
        CreditsScreen(contentPadding = contentPadding, onBack = { showCredits = false })
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    fun open(url: String) {
        if (url.isBlank()) {
            Toast.makeText(context, "敬请期待", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
    }
    fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        Toast.makeText(context, "正在检查更新…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val info = UpdateChecker.check(force = true)
            checkingUpdate = false
            when {
                info == null -> Toast.makeText(context, "检查失败，请稍后重试", Toast.LENGTH_SHORT).show()
                info.hasUpdate -> {
                    Toast.makeText(context, "发现新版本 v${info.versionName}", Toast.LENGTH_SHORT).show()
                    open(info.releaseUrl.ifBlank { info.apkUrl })
                }
                else -> Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = { SmallTopAppBar(title = "关于", scrollBehavior = scrollBehavior) },
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
            item { LogoHeader() }

            item { SmallTitle("链接") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "检查更新",
                        summary = if (checkingUpdate) "正在检查…" else "当前 v${BuildConfig.VERSION_NAME}",
                        onClick = { checkUpdate() },
                    )
                    ArrowPreference(title = "GitHub 仓库", summary = "项目开源地址", onClick = { open(URL_GITHUB) })
                    ArrowPreference(title = "项目文档", summary = "使用说明 / 常见问题", onClick = { open(URL_DOCS) })
                    ArrowPreference(title = "爱发电", summary = "赞助支持开发", onClick = { open(URL_AFDIAN) })
                    ArrowPreference(title = "QQ 群聊", summary = "群号 228567742 · 交流与反馈", onClick = { open(URL_QQ_GROUP) })
                    ArrowPreference(title = "酷安主页", summary = "动态与更新", onClick = { open(URL_COOLAPK) })
                }
            }

            item { SmallTitle("鸣谢") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "鸣谢项目",
                        summary = "本项目参考 / 借鉴的开源项目",
                        onClick = { showCredits = true },
                    )
                }
            }

            item { SmallTitle("开源引用") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OPEN_SOURCE_REFS.forEach { ref ->
                        if (ref.url.isBlank()) {
                            InfoRow(ref.name, ref.usage)
                        } else {
                            ArrowPreference(title = ref.name, summary = ref.usage, onClick = { open(ref.url) })
                        }
                    }
                }
            }

            item { SmallTitle("设备信息") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("Android 版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow("应用版本", "v${BuildConfig.VERSION_NAME}")
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CreditsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
    }

    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "鸣谢项目",
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
            item { SmallTitle("本项目站在以下开源项目的肩膀上") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    CREDIT_PROJECTS.forEach { p ->
                        ArrowPreference(title = p.name, summary = p.role, onClick = { open(p.url) })
                    }
                }
            }
            item { SmallTitle("许可") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    InfoRow("REAREye / MRSS", "GPL-3.0")
                    InfoRow("词幕 Lyricon / LyricProvider", "Apache-2.0")
                    InfoRow("本项目", "GPL-3.0")
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LogoHeader() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(90.dp).clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text("ZHITool", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            "小稚的 Xiaomi 17 系列背屏工具",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun InfoRow(title: String, summary: String) {
    BasicComponent(
        title = title,
        summary = summary,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    )
}
