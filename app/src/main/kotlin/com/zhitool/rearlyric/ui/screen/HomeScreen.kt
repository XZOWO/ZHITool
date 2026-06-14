package com.zhitool.rearlyric.ui.screen

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.R
import androidx.compose.ui.platform.LocalContext
import com.zhitool.rearlyric.lyric.ConfigStore
import com.zhitool.rearlyric.lyric.LyricBus
import com.zhitool.rearlyric.lyric.ProjectionState
import io.github.proify.lyricon.lyric.model.Song
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun HomeScreen(
    rootGranted: Boolean,
    lyricConnected: Boolean,
    projected: Boolean,
    lsposedActive: Boolean,
    onToggleProject: () -> Unit,
    contentPadding: PaddingValues,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val song by LyricBus.songFlow.collectAsState()
    val position by LyricBus.positionFlow.collectAsState()
    val playing by LyricBus.playingFlow.collectAsState()
    val playerPackage by LyricBus.playerPackage.collectAsState()
    val cover by LyricBus.cover.collectAsState()
    val projectionEnabled by ProjectionState.enabled.collectAsState()
    val context = LocalContext.current
    val currentLyric = rememberCurrentLyric(song, position)
    val coverBitmap by produceState<ImageBitmap?>(null, cover) {
        value = cover?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = "ZHITool", scrollBehavior = scrollBehavior) },
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
                HeroCard(
                    rootGranted = rootGranted,
                    song = song,
                    playing = playing,
                    playerPackage = playerPackage,
                    currentLyric = currentLyric,
                    coverBitmap = coverBitmap,
                )
            }
            item {
                Button(
                    onClick = onToggleProject,
                    enabled = rootGranted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = if (projected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (projected) "收回背屏" else "投到背屏")
                }
            }
            item {
                Button(
                    onClick = { ConfigStore.saveProjectionEnabled(context, !projectionEnabled) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = if (projectionEnabled) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (projectionEnabled) "停止投放" else "开始投放")
                }
            }
            item { SmallTitle("状态") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    StatusRow(Icons.Rounded.Security, "Root 权限", if (rootGranted) "已授权" else "未授权", rootGranted)
                    StatusRow(Icons.Rounded.GraphicEq, "歌词数据源", if (lyricConnected) "已连接" else "等待中", lyricConnected)
                    StatusRow(Icons.Rounded.Security, "LSPosed 模块", if (lsposedActive) "已激活" else "未激活", lsposedActive)
                }
            }
            item { SmallTitle("设备信息") }
            item { DeviceInfoCard() }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/**
 * 顶部卡片：背屏歌词未就绪（无歌曲）时保持原样式——圆形程序图标 + 状态文案；
 * 有歌曲时左侧换成背屏同款匀速转动封面（暂停即停转），右侧照搬"正在播放"信息。
 */
@Composable
private fun HeroCard(
    rootGranted: Boolean,
    song: Song?,
    playing: Boolean,
    playerPackage: String?,
    currentLyric: String?,
    coverBitmap: ImageBitmap?,
) {
    Card(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (song != null && coverBitmap != null) {
                RotatingCover(bitmap = coverBitmap, playing = playing, size = 56.dp)
            } else {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = null,
                    modifier = Modifier.size(54.dp).clip(CircleShape),
                )
            }
            Spacer(Modifier.size(14.dp))
            if (song != null) {
                NowPlayingInfo(
                    song = song,
                    playing = playing,
                    playerPackage = playerPackage,
                    currentLyric = currentLyric,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column {
                    Text(
                        text = if (rootGranted) "背屏歌词已就绪" else "等待 Root 授权",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "播放音乐时在背屏实时显示歌词",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 正在播放信息块（照搬原"正在播放"卡片内容）。 */
@Composable
private fun NowPlayingInfo(
    song: Song,
    playing: Boolean,
    playerPackage: String?,
    currentLyric: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = song.name ?: "未知歌曲",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = song.artist ?: "",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (playing) "播放中" else "已暂停",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (playing) Color(0xFF34C759) else Color(0xFFFFB84D),
            )
            if (!playerPackage.isNullOrBlank()) {
                Text(
                    text = " · ",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = playerPackage,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!currentLyric.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = currentLyric,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 背屏同款匀速转动封面：播放时按 16s/圈匀速转，暂停即停转并保持当前角度。 */
@Composable
private fun RotatingCover(bitmap: ImageBitmap, playing: Boolean, size: Dp) {
    var angle by remember(bitmap) { mutableFloatStateOf(0f) }
    LaunchedEffect(bitmap, playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    angle = (angle + (360f / 16f) * ((now - last) / 1_000_000_000f)) % 360f
                }
                last = now
            }
        }
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle }
            .clip(CircleShape),
    )
}

/** 设备信息卡片（与关于页一致）。 */
@Composable
private fun DeviceInfoCard() {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        InfoRow("Android 版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoRow("应用版本", "v${BuildConfig.VERSION_NAME}")
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

@Composable
private fun StatusRow(icon: ImageVector, title: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(text = title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (ok) Color(0xFF34C759) else Color(0xFFFF6B6B),
        )
    }
}

@Composable
private fun rememberCurrentLyric(song: Song?, position: Long): String? {
    val lyrics = song?.lyrics.orEmpty()
    val current = lyrics.lastOrNull { it.begin <= position }?.text
        ?.takeIf { it.isNotBlank() }
    return current ?: lyrics.firstOrNull()?.text?.takeIf { it.isNotBlank() }
}
