package com.zhitool.rearlyric.lyric

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.core.content.ContextCompat
import com.zhitool.rearlyric.R
import com.zhitool.rearlyric.core.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.zhitool.rearlyric.rear.RearProjector
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 歌词订阅前台服务。
 *
 * 作为 Lyricon（词幕生态）订阅端：连接 central、接收 [Song] 与播放进度，写入 [LyricBus]。
 *
 * 投屏逻辑对齐词幕的显示策略：每次拿到带歌词的新歌就触发一次投屏（中间有间断也重新投），
 * 活跃播放器消失（词幕隐藏）时收回。用户也可在主页按钮手动投/收。
 */
class LyricService : Service() {

    private var subscriber: LyriconSubscriber? = null
    private val projectExecutor = Executors.newSingleThreadExecutor()
    private val coverExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 观察投放主开关与投放状态：据此收回/重投 + 刷新通知按钮。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var currentPlayerPackage: String? = null

    @Volatile
    private var coverRefreshToken = 0

    /** 切歌时刻：用于封面时间戳校验，拒绝"比当前歌还旧"的封面。 */
    @Volatile
    private var songChangedAt = 0L          // SystemClock.elapsedRealtime（保留备用）

    @Volatile
    private var songChangedAtWall = 0L      // System.currentTimeMillis，配封面文件 lastModified

    private fun markSongChanged() {
        songChangedAt = SystemClock.elapsedRealtime()
        songChangedAtWall = System.currentTimeMillis()
    }

    private val coverObservers = mutableListOf<FileObserver>()

    /**
     * 背屏保活（参照 MRSS RearScreenKeeperService）：锁屏后副屏会自动熄屏/被锁，
     * 歌词 Activity 随之被 stop 卡死。投屏且播放期间持续向 display 1 发 WAKEUP 防熄屏。
     */
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val wakeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var wakeLoopRunning = false

    private val wakeRunnable: Runnable = Runnable { tickRearWakeup() }

    /**
     * 息屏保活锁（MRSS RearScreenKeeperService 同款 SCREEN_BRIGHT）：
     * 仅靠 WAKEUP 只能让副屏亮着——设备整体休眠仍会把背屏 Activity onStop，
     * Compose 帧时钟随 onStop 暂停，歌词"显示但不动"。持锁保持设备非休眠，
     * Activity 才能保持 resumed 持续渲染。仅在投屏 + 播放期间持有。
     */
    private var rearWakeLock: PowerManager.WakeLock? = null

    /** 上次（重新）投屏时间，用作冻结自愈的宽限期 + 节流。 */
    @Volatile
    private var lastShowAt = 0L

    /** 暂停起始时刻（elapsedRealtime，0=未暂停）；暂停超过 [PAUSE_RETRACT_MS] 收回背屏。 */
    @Volatile
    private var pausedSince = 0L

    /** SystemUI hook 直接广播来的封面字节：当前播放器即实时更新背屏封面（绕开文件/通知权限）。 */
    private val coverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CoverStorage.ACTION_COVER_UPDATED) return
            val pkg = intent.getStringExtra(CoverStorage.EXTRA_PACKAGE) ?: return
            if (pkg != currentPlayerPackage) return
            val bytes = intent.getByteArrayExtra(CoverStorage.EXTRA_COVER)
            if (bytes != null && bytes.isNotEmpty()) {
                LyricBus.setCover(bytes)
            } else {
                refreshCover(retry = false)
            }
        }
    }

    /** 通知按钮：投/收背屏（手动）、停止/开始投放（主开关）。 */
    private val notifActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(EXTRA_NOTIF_CMD)) {
                CMD_TOGGLE_PROJECT -> if (LyricBus.projected.value) hideRear() else showRear()
                CMD_TOGGLE_ENABLED -> ConfigStore.saveProjectionEnabled(this@LyricService, !ProjectionState.current)
            }
        }
    }

    /** 向 SystemUI 里的 hook 请求当前封面（冷启动/切播放器时本地还没收到广播）。 */
    private fun requestCoverFromHook(packageName: String?) {
        val pkg = packageName ?: return
        runCatching {
            sendBroadcast(
                Intent(CoverStorage.ACTION_COVER_REQUEST)
                    .setPackage(SYSTEM_UI_PACKAGE)
                    .putExtra(CoverStorage.EXTRA_PACKAGE, pkg)
            )
        }
    }

    /** 解锁/亮屏后重申投屏：锁屏期间背屏 Activity 可能被系统干掉，恢复时拉回来。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    if (LyricBus.projected.value &&
                        LyricBus.playing.value &&
                        LyricBus.song.value != null &&
                        allowed() &&
                        isRearFrameStale()
                    ) {
                        showRear()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startSubscriber()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
        ContextCompat.registerReceiver(
            this,
            coverReceiver,
            IntentFilter(CoverStorage.ACTION_COVER_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            notifActionReceiver,
            IntentFilter(ACTION_NOTIF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 主开关切换：停止→收回；开始→若正在播放有词则重投。
        serviceScope.launch {
            ProjectionState.enabled.drop(1).collect { enabled ->
                if (!enabled) {
                    hideRear()
                } else if (LyricBus.playing.value && LyricBus.song.value != null && allowed()) {
                    showRear()
                }
            }
        }
        // 投放状态/主开关变化时刷新通知按钮文案。
        serviceScope.launch {
            combine(LyricBus.projected, ProjectionState.enabled) { p, e -> p to e }.collect {
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
        wakeLoopRunning = true
        wakeHandler.post(wakeRunnable)
    }

    private fun tickRearWakeup() {
        if (!wakeLoopRunning) return
        runCatching {
            wakeExecutor.execute {
                val now = SystemClock.elapsedRealtime()
                val projected = LyricBus.projected.value
                val playing = LyricBus.playing.value
                // 暂停超过阈值收回背屏，让出原生背屏（再次播放时 onPlaybackStateChanged 会重投）。
                if (projected && !playing) {
                    if (pausedSince == 0L) {
                        pausedSince = now
                    } else if (now - pausedSince > PAUSE_RETRACT_MS) {
                        pausedSince = 0L
                        hideRear()
                    }
                } else {
                    pausedSince = 0L
                }
                val active = wakeLoopRunning && projected && playing
                updateRearWakeLock(active)
                if (active && RootShell.available) {
                    RootShell.run("input -d 1 keyevent KEYCODE_WAKEUP")
                    maybeRekickFrozenRear()
                }
                if (wakeLoopRunning) wakeHandler.postDelayed(wakeRunnable, WAKE_INTERVAL_MS)
            }
        }.onFailure {
            if (wakeLoopRunning) wakeHandler.postDelayed(wakeRunnable, WAKE_INTERVAL_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateRearWakeLock(active: Boolean) {
        if (active) {
            if (rearWakeLock?.isHeld != true) {
                runCatching {
                    val pm = getSystemService(PowerManager::class.java)
                    rearWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "ZHITool::RearLyricKeeper"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    Log.i(TAG, "rear wake lock acquired")
                }.onFailure { Log.w(TAG, "acquire wake lock failed", it) }
            }
        } else {
            rearWakeLock?.takeIf { it.isHeld }?.let {
                runCatching { it.release() }
                Log.i(TAG, "rear wake lock released")
            }
        }
    }

    /** 投屏 + 播放中却超过阈值没有渲染心跳 → 背屏 Activity 被 stop 冻结，重新拉起自愈。 */
    private fun maybeRekickFrozenRear() {
        if (!isRearFrameStale()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShowAt < REKICK_MIN_INTERVAL_MS) return
        Log.w(TAG, "rear lyric frozen (${now - LyricBus.lastFrameAt}ms since last frame), rekick")
        showRear()
    }

    private fun isRearFrameStale(): Boolean {
        val lastFrame = LyricBus.lastFrameAt
        return lastFrame == 0L ||
            SystemClock.elapsedRealtime() - lastFrame > FREEZE_THRESHOLD_MS
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSubscriber() {
        runCatching {
            // 若设备未安装独立的 Lyricon 应用，则启动自带的桥接 central。
            if (!isLyriconInstalled()) {
                Log.i(TAG, "Lyricon app not found, bootstrapping bundled central")
                BridgeCentral.initialize(this)
                BridgeCentral.sendBootCompleted()
            }
            val sub = LyriconFactory.createSubscriber(this)
            sub.subscribeActivePlayer(playerListener)
            sub.register()
            subscriber = sub
            Log.i(TAG, "Lyricon subscriber registered")
        }.onFailure { Log.e(TAG, "startSubscriber failed", it) }
    }

    private val playerListener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            currentPlayerPackage = providerInfo?.playerPackageName
            LyricBus.playerPackage.value = currentPlayerPackage
            // 活跃播放器消失 → 词幕隐藏 → 收回背屏
            if (providerInfo == null) {
                stopCoverObservers()
                LyricBus.reset()
                hideRear()
            } else {
                restartCoverObservers(providerInfo.playerPackageName)
                markSongChanged()
                requestCoverFromHook(providerInfo.playerPackageName)
                refreshCover(retry = true)
            }
        }

        override fun onSongChanged(song: Song?) {
            // 切歌进度归零：provider 的 onPositionChanged 通常晚于 onSongChanged 到达，
            // 不归零的话新歌会沿用旧歌切歌时的进度（要到新歌首句才回到开头）。
            // 先于 song 置 0：中间态变成"旧歌 + 进度 0"（已被 FullLyricView 守卫保持停靠），
            // 新歌默认从头；真实进度随后由 onPositionChanged 刷新。
            LyricBus.setPosition(0L)
            LyricBus.song.value = song
            markSongChanged()
            requestCoverFromHook(currentPlayerPackage)
            refreshCover(retry = true)
            // 每次拿到带歌词的新歌都触发一次投屏（对齐词幕"有显示就投"），按包配置过滤 + 投放主开关。
            if (song != null && allowed() && ProjectionState.current) showRear()
        }

        override fun onReceiveText(text: String?) = Unit

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            LyricBus.playing.value = isPlaying
            if (isPlaying) refreshCover(retry = false)
            if (isPlaying && LyricBus.song.value != null && allowed() && ProjectionState.current) showRear()
        }

        override fun onPositionChanged(position: Long) = LyricBus.setPosition(position)

        override fun onSeekTo(position: Long) = LyricBus.setPosition(position)

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }

    private fun allowed(): Boolean = AppFilterState.current.allows(currentPlayerPackage)

    /**
     * 拉取当前歌曲封面。来源优先级：精确匹配封面文件 > 通知使用权(MediaSession) > 最新封面文件。
     * 时间戳校验：重试期间只采用"不早于切歌时刻"的封面（拒绝上一首）；当前完全无封面时才兜底取最新一张。
     * 已有封面则保留，等 SystemUI hook 在会话元数据变化时字节广播推送替换，避免被旧图覆盖。
     */
    private fun refreshCover(retry: Boolean) {
        val token = ++coverRefreshToken
        val packageName = currentPlayerPackage ?: run {
            LyricBus.cover.value = null
            return
        }
        val song = LyricBus.song.value
        coverExecutor.execute {
            val attempts = if (retry) 12 else 1
            repeat(attempts) { index ->
                if (token != coverRefreshToken) return@execute
                val bytes = loadCoverBytes(packageName, song, allowStale = false)
                if (bytes != null) {
                    LyricBus.setCover(bytes)
                    return@execute
                }
                if (attempts > 1 && index < attempts - 1) Thread.sleep(220)
            }
            if (token != coverRefreshToken) return@execute
            // 没拿到当前歌的新封面：仅当此刻完全无封面时兜底显示最新一张（避免全无封面）；
            // 已有封面则保留，等实时推送替换，不让旧图覆盖。
            if (LyricBus.cover.value == null) {
                loadCoverBytes(packageName, song, allowStale = true)?.let { LyricBus.setCover(it) }
            }
        }
    }

    private fun loadCoverBytes(packageName: String, song: Song?, allowStale: Boolean): ByteArray? {
        val ctx = CoverStorage.moduleContext(this) ?: this
        val title = song?.name?.trim().orEmpty()
        val artist = song?.artist?.trim().orEmpty()
        val hasSongKey = title.isNotBlank() || artist.isNotBlank()

        // 精确匹配（按歌曲信息）的封面文件：一定是当前歌的，直接用。
        if (hasSongKey) {
            val cachedFile = CoverStorage.cachedCoverFile(ctx, packageName, title, artist)
            if (cachedFile.isFile && cachedFile.length() > 0L) {
                return runCatching { cachedFile.readBytes() }.getOrNull()
            }
        }

        // 最新封面文件兜底：时间戳校验拒绝上一首（allowStale 时放行）。封面主路径走 hook 字节广播。
        val latestFile = CoverStorage.latestCoverFile(ctx, packageName)
        if (latestFile.isFile && latestFile.length() > 0L) {
            val fresh = latestFile.lastModified() >= songChangedAtWall - COVER_FRESH_SLACK_MS
            if (fresh || allowStale) {
                return runCatching { latestFile.readBytes() }.getOrNull()
            }
        }
        return null
    }

    private fun showRear() {
        lastShowAt = SystemClock.elapsedRealtime()
        projectExecutor.execute { RearProjector.show() }
    }

    private fun hideRear() {
        projectExecutor.execute { RearProjector.hide() }
    }

    private fun isLyriconInstalled(): Boolean = LYRICON_PACKAGES.any { pkg ->
        runCatching {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        }.isSuccess
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "背屏歌词", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val projected = LyricBus.projected.value
        val enabled = ProjectionState.current
        val icon = Icon.createWithResource(this, R.mipmap.ic_launcher_foreground)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZHITool")
            .setContentText(if (enabled) "背屏歌词运行中" else "已停止投放")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    icon,
                    if (projected) "收回背屏" else "投到背屏",
                    notifActionPending(CMD_TOGGLE_PROJECT, 1),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    icon,
                    if (enabled) "停止投放" else "开始投放",
                    notifActionPending(CMD_TOGGLE_ENABLED, 2),
                ).build()
            )
            .build()
    }

    private fun notifActionPending(cmd: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(ACTION_NOTIF).setPackage(packageName).putExtra(EXTRA_NOTIF_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun onDestroy() {
        super.onDestroy()
        wakeLoopRunning = false
        wakeHandler.removeCallbacksAndMessages(null)
        rearWakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        rearWakeLock = null
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(coverReceiver) }
        runCatching { unregisterReceiver(notifActionReceiver) }
        runCatching { serviceScope.cancel() }
        runCatching {
            subscriber?.unregister()
            subscriber?.destroy()
        }
        subscriber = null
        runCatching {
            coverRefreshToken++
        }
        stopCoverObservers()
        projectExecutor.shutdownNow()
        coverExecutor.shutdownNow()
        wakeExecutor.shutdownNow()
        LyricBus.reset()
    }

    private fun restartCoverObservers(packageName: String?) {
        stopCoverObservers()
        if (packageName.isNullOrBlank()) return
        val ctx = CoverStorage.moduleContext(this) ?: this
        val packageDir = CoverStorage.packageDir(ctx, packageName)
        val cacheDir = File(packageDir, "caches").apply { mkdirs() }
        coverObservers += buildCoverObserver(packageDir)
        coverObservers += buildCoverObserver(cacheDir)
        coverObservers.forEach { observer ->
            runCatching { observer.startWatching() }
                .onFailure { Log.w(TAG, "start cover observer failed for ${observer.javaClass.simpleName}", it) }
        }
    }

    private fun stopCoverObservers() {
        coverObservers.forEach { observer ->
            runCatching { observer.stopWatching() }
        }
        coverObservers.clear()
    }

    private fun buildCoverObserver(dir: File): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MODIFY
        return object : FileObserver(dir.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrBlank()) return
                if (!path.endsWith(".png", ignoreCase = true)) return
                refreshCover(retry = false)
            }
        }
    }

    companion object {
        private const val TAG = "ZhiLyricService"
        private const val CHANNEL_ID = "zhi_lyric"
        private const val NOTIFICATION_ID = 0x21

        /** 背屏防熄屏唤醒间隔（MRSS 用 100ms；我们走 su 管道开销更大，500ms 足够防熄）。 */
        private const val WAKE_INTERVAL_MS = 500L

        /** 渲染心跳超过该时长没更新视为冻结（正常 60fps 下心跳间隔 ~16ms）。 */
        private const val FREEZE_THRESHOLD_MS = 3_000L

        /** 两次自愈重投的最小间隔，兼作初次投屏的宽限期（拉起+移屏约需 1~2s）。 */
        private const val REKICK_MIN_INTERVAL_MS = 8_000L

        /** 封面时间戳校验宽限：音乐 app 常在切歌检测前略微更新会话封面，留点提前量避免误判为旧图。 */
        private const val COVER_FRESH_SLACK_MS = 3_000L

        /** 暂停超过此时长收回背屏（让出原生背屏）；再次播放重投。 */
        private const val PAUSE_RETRACT_MS = 3 * 60 * 1000L

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        private const val ACTION_NOTIF = "com.zhitool.rearlyric.action.NOTIF_CMD"
        private const val EXTRA_NOTIF_CMD = "cmd"
        private const val CMD_TOGGLE_PROJECT = "toggle_project"
        private const val CMD_TOGGLE_ENABLED = "toggle_enabled"
        private val LYRICON_PACKAGES = listOf(
            "io.github.proify.lyricon",
            "io.github.proify.lyricon.core",
        )

        fun start(context: Context) {
            val intent = Intent(context, LyricService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LyricService::class.java))
        }
    }
}
