package com.zhitool.rearlyric.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.zhitool.rearlyric.ZhiApplication
import com.zhitool.rearlyric.core.RootShell
import com.zhitool.rearlyric.lyric.LyricBus
import com.zhitool.rearlyric.lyric.LyricService
import com.zhitool.rearlyric.rear.RearProjector
import com.zhitool.rearlyric.ui.glass.FloatingBottomBar
import com.zhitool.rearlyric.ui.glass.FloatingBottomBarItem
import com.zhitool.rearlyric.ui.icons.LyricNoteIcon
import com.zhitool.rearlyric.ui.screen.AboutScreen
import com.zhitool.rearlyric.ui.screen.ConfigScreen
import com.zhitool.rearlyric.ui.screen.HomeScreen
import com.zhitool.rearlyric.ui.screen.ToolsScreen
import com.zhitool.rearlyric.ui.theme.ZhiTheme
import kotlin.concurrent.thread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class NavTab(val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {

    private var rootGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acquireRootThenStart()

        setContent {
            ZhiTheme {
                ZhiApp(rootGranted = rootGranted)
            }
        }
    }

    private fun acquireRootThenStart() {
        thread(name = "zhi-root") {
            val ok = RootShell.refresh()
            runOnUiThread {
                rootGranted = ok
                if (ok) LyricService.start(this)
            }
        }
    }
}

@Composable
private fun ZhiApp(rootGranted: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            NavTab("主页", Icons.Rounded.Cottage),
            NavTab("工具", Icons.Rounded.Build),
            NavTab("歌词", LyricNoteIcon),
            NavTab("关于", Icons.Rounded.Info),
        )
    }
    val song by LyricBus.songFlow.collectAsState()
    val projected by LyricBus.projected.collectAsState()
    val lsposedActive by ZhiApplication.lsposedActive.collectAsStateWithLifecycle()

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPadding = PaddingValues(bottom = 104.dp + navInset)

    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }

    Scaffold { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        (fadeIn(tween(210, 50)) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) {
                            if (forward) it / 9 else -it / 9
                        }) togetherWith (fadeOut(tween(130)) + slideOutHorizontally(tween(190)) {
                            if (forward) -it / 12 else it / 12
                        })
                    },
                    label = "page",
                ) { page ->
                    when (page) {
                        0 -> HomeScreen(
                            rootGranted = rootGranted,
                            lyricConnected = song != null,
                            projected = projected,
                            lsposedActive = lsposedActive,
                            onToggleProject = {
                                if (projected) {
                                    RearProjector.hide()
                                } else {
                                    thread(name = "zhi-project") { RearProjector.show() }
                                }
                            },
                            contentPadding = contentPadding,
                        )
                        1 -> ToolsScreen(contentPadding)
                        2 -> ConfigScreen(contentPadding)
                        else -> AboutScreen(contentPadding)
                    }
                }
            }

            ZhiNavBar(
                tabs = tabs,
                selectedIndex = selected,
                onSelected = { selected = it },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp + navInset),
            )
        }
    }
}

@Composable
private fun ZhiNavBar(
    tabs: List<NavTab>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier,
) {
    FloatingBottomBar(
        modifier = modifier,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        backdrop = backdrop,
        tabsCount = tabs.size,
        isBlurEnabled = true,
    ) {
        tabs.forEachIndexed { index, tab ->
            FloatingBottomBarItem(
                onClick = { onSelected(index) },
                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = tab.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}
