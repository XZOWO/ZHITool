package com.zhitool.rearlyric.lyric

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zhitool.rearlyric.core.MediaControl
import com.zhitool.rearlyric.core.ForegroundCoordinator
import com.zhitool.rearlyric.core.ServiceNotice
import com.zhitool.rearlyric.rear.RearProjector
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

    /** 开机/进程重建后的 Root 探测独立于投影队列，并发触发时只保留一份。 */
    private val rootInitLock = Any()
    private var rootInitJob: Job? = null

    /** SuperLyric 每句都会重建 Song；只让真正变化的会话 id 触发一次自动投放。 */
    private var lastAutoProjectedSuperSongId: String? = null

    @Volatile
    private var currentPlayerPackage: String? = null

    @Volatile
    private var coverRefreshToken = 0

    /** 切歌时刻：用于封面时间戳校验，拒绝"比当前歌还旧"的封面。 */
    @Volatile
    private var songChangedAt = 0L          // SystemClock.elapsedRealtime（保留备用）

    @Volatile
    private var songChangedAtWall = 0L      // System.currentTimeMillis，配封面文件 lastModified

    /** 最近一次「自系统会话直读到进度」的墙钟（elapsedRealtime）；据此把词幕上报的进度退为兜底。 */
    @Volatile
    private var lastSessionPlaybackAt = 0L

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
            if (pkg != activePlayerPackage()) return
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
        _running.value = true
        ForegroundCoordinator.started(ForegroundCoordinator.TAG_LYRIC)
        startForeground(SHARED_NOTIF_ID, buildSharedNotification(this))
        // MainActivity 不一定会在开机/进程重建后出现，不能再依赖它独占 Root 初始化。
        ensureRootReady("service-create")
        applyLyricSource(LyricSourceState.current)
        // 歌词数据源切换（词幕 ↔ SuperLyric）：启停对应来源并清空旧数据。
        serviceScope.launch {
            LyricSourceState.flow.drop(1).collect { applyLyricSource(it) }
        }
        // SuperLyric 源是直接写 LyricBus.song（无词幕 onSongChanged），这里据歌曲出现触发自动投放。
        serviceScope.launch {
            LyricBus.songFlow.collect { song ->
                if (LyricSourceState.current == LyricSource.SUPERLYRIC && song != null) {
                    val songId = song.id?.takeIf { it.isNotBlank() }
                    if (songId != lastAutoProjectedSuperSongId) {
                        lastAutoProjectedSuperSongId = songId
                        maybeAutoProject("superlyric-song")
                    }
                } else if (song == null) {
                    lastAutoProjectedSuperSongId = null
                }
            }
        }
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
        // 主开关切换：停止→收回；启动→只要有歌就投（不论播放/暂停，对齐"启动后任何状态都自动投"）。
        serviceScope.launch {
            ProjectionState.enabled.drop(1).collect { enabled ->
                if (!enabled) {
                    hideRear()
                } else if (LyricBus.song.value != null && allowed()) {
                    showRear()
                }
            }
        }
        // 自己掌握播放进度：周期性从系统会话直读进度+播放态喂给 LyricBus，独立于词幕的进度上报。
        // 词幕只管提供整首逐字歌词；进度由我们读 → 词幕断了/不报进度时歌词仍实时跟着跑（不再靠改版词幕）。
        var lastSuperSongKey: String? = null
        serviceScope.launch {
            while (true) {
                val src = LyricSourceState.current
                val sessionPackage = activePlayerPackage()
                if (src == LyricSource.SUPERLYRIC) {
                    // SuperLyric 源：进度 + 歌名/歌手/封面都从系统会话取（权威、随切歌可靠变、进度真实可 seek）。
                    val pb = withContext(Dispatchers.Default) {
                        MediaControl.readPlayback(this@LyricService, sessionPackage)
                    }
                    if (pb != null) {
                        LyricBus.setPosition(pb.positionMs)
                        if (LyricBus.playing.value != pb.playing) LyricBus.playing.value = pb.playing
                        lastSessionPlaybackAt = SystemClock.elapsedRealtime()
                    }
                    val meta = withContext(Dispatchers.Default) {
                        MediaControl.readMetadata(this@LyricService, sessionPackage)
                    }
                    // QQ/小米歌词态会把 TITLE 变成当前句、ARTIST 变成「歌名-歌手」；
                    // 由状态化归一化器提交稳定显示信息和完整歌曲身份，逐句 TITLE 不再触发换歌。
                    val normalized = SuperLyricSource.onMediaMeta(meta?.title, meta?.artist)
                    // 完整歌名+歌手身份变化才向 SystemUI hook 请求封面；同名不同歌手也能正确刷新。
                    val normalizedKey = normalized?.songKey
                    if (normalizedKey != null && normalizedKey != lastSuperSongKey) {
                        lastSuperSongKey = normalizedKey
                        requestCoverFromHook(sessionPackage)
                        refreshCover(retry = true)
                    }
                } else {
                    lastSuperSongKey = null
                    if (LyricBus.song.value != null) {
                        val pb = withContext(Dispatchers.Default) {
                            MediaControl.readPlayback(this@LyricService, sessionPackage)
                        }
                        if (pb != null) {
                            LyricBus.setPosition(pb.positionMs)
                            if (LyricBus.playing.value != pb.playing) LyricBus.playing.value = pb.playing
                            lastSessionPlaybackAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
                delay(SESSION_POS_POLL_MS)
            }
        }
        // 投放状态/主开关变化时刷新通知按钮文案。
        serviceScope.launch {
            combine(LyricBus.projected, ProjectionState.enabled) { p, e -> p to e }.collect {
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(SHARED_NOTIF_ID, buildSharedNotification(this@LyricService))
                }
            }
        }
        wakeLoopRunning = true
        wakeHandler.post(wakeRunnable)
    }

    /**
     * 开机广播或 START_STICKY 单独恢复服务时，App 主界面没有机会调用 RootShell.refresh()。
     * Root 管理器可能晚于 BOOT_COMPLETED 就绪，因此在 IO 协程中退避重试；不占用投影单线程队列，
     * 也不直接调用 RearProjector，Root 成功后统一回到自动投放入口，避免同一首歌重复投放。
     */
    private fun ensureRootReady(reason: String) {
        if (RootShell.available) return
        synchronized(rootInitLock) {
            if (rootInitJob?.isActive == true) return
            rootInitJob = serviceScope.launch(Dispatchers.IO) {
                for ((index, waitMs) in ROOT_INIT_RETRY_DELAYS_MS.withIndex()) {
                    if (waitMs > 0L) delay(waitMs)
                    val ready = RootShell.available ||
                        runCatching { RootShell.refresh() }.getOrDefault(false)
                    Log.i(TAG, "background root init ${index + 1}/${ROOT_INIT_RETRY_DELAYS_MS.size} [$reason] -> $ready")
                    if (ready) {
                        withContext(Dispatchers.Main.immediate) {
                            maybeAutoProject("root-ready:$reason")
                        }
                        return@launch
                    }
                }
                Log.w(TAG, "background root init exhausted [$reason]; retry on next projection request")
            }
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 覆盖服务实例仍在但收到重复启动，以及上一轮 Root 重试已经耗尽的情况。
        ensureRootReady("service-start")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSubscriber() {
        runCatching {
            // central 由 SystemUI 进程托管：装了词幕用词幕的 central；没装则 ZHITool 的 SystemUI hook 自己起一个
            // （见 SystemUIHooker.hostCentralIfNeeded）。订阅端只认 SystemUI 的 central，故这里不在本进程起 central
            // （起了也没人连——历史 bug：自带 central 起错进程）。
            val sub = LyriconFactory.createSubscriber(this)
            // 连接状态日志：排查"读不到歌词源"——TIMEOUT=SystemUI 里没有可连的 central（词幕没托管/版本不匹配/ZHITool 未起）。
            sub.addConnectionListener(object : io.github.proify.lyricon.subscriber.ConnectionListener {
                override fun onConnected(subscriber: LyriconSubscriber) { Log.i(TAG, "central connected") }
                override fun onReconnected(subscriber: LyriconSubscriber) { Log.i(TAG, "central reconnected") }
                override fun onDisconnected(subscriber: LyriconSubscriber) { Log.w(TAG, "central disconnected") }
                override fun onConnectTimeout(subscriber: LyriconSubscriber) {
                    Log.w(TAG, "central connect TIMEOUT — SystemUI 里没有可连的 central（词幕未托管/版本不匹配/ZHITool 未在 SystemUI 起 central）")
                }
            })
            sub.subscribeActivePlayer(playerListener)
            sub.register()
            subscriber = sub
            Log.i(TAG, "Lyricon subscriber registered")
        }.onFailure { Log.e(TAG, "startSubscriber failed", it) }
    }

    private fun stopSubscriber() {
        val sub = subscriber ?: return
        runCatching {
            sub.unregister()
            sub.destroy()
        }
        subscriber = null
    }

    /** 按选中的歌词数据源启停对应来源（词幕订阅端 ↔ SuperLyric 接收器），并清空旧来源残留。 */
    private fun applyLyricSource(source: LyricSource) {
        Log.i(TAG, "applyLyricSource: $source")
        lastAutoProjectedSuperSongId = null
        LyricBus.reset()
        when (source) {
            LyricSource.LYRICON -> {
                SuperLyricSource.stop()
                if (subscriber == null) startSubscriber()
            }
            LyricSource.SUPERLYRIC -> {
                stopSubscriber()
                SuperLyricSource.start()
            }
        }
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
            // 拿到新歌即按「主开关 + 包过滤」自动投屏（对齐词幕"有显示就投"）。
            maybeAutoProject("songChanged")
        }

        override fun onReceiveText(text: String?) = Unit

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            LyricBus.playing.value = isPlaying
            if (isPlaying) refreshCover(retry = false)
            // 开始播放 / 暂停播放都自动投——对齐"启动歌词投放后任何状态都投到背屏"。
            maybeAutoProject(if (isPlaying) "play" else "pause")
        }

        // 词幕上报的进度仅作兜底：近期能从系统会话直读到进度时（[lastSessionPlaybackAt] 在 TTL 内）以会话为准，忽略词幕进度。
        override fun onPositionChanged(position: Long) {
            if (SystemClock.elapsedRealtime() - lastSessionPlaybackAt > SESSION_POS_TTL_MS) LyricBus.setPosition(position)
        }

        override fun onSeekTo(position: Long) {
            if (SystemClock.elapsedRealtime() - lastSessionPlaybackAt > SESSION_POS_TTL_MS) LyricBus.setPosition(position)
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }

    /** SuperLyric 的发布者包名写在 LyricBus；lyricon 则由 ProviderInfo 维护私有字段。 */
    private fun activePlayerPackage(): String? =
        if (LyricSourceState.current == LyricSource.SUPERLYRIC) {
            LyricBus.playerPackage.value
        } else {
            currentPlayerPackage
        }

    private fun allowed(): Boolean = AppFilterState.current.allows(activePlayerPackage())

    /**
     * 拉取当前歌曲封面。来源优先级：SystemUI hook 实时字节 > 精确匹配封面文件 > 最新封面文件。
     * 时间戳校验：重试期间只采用"不早于切歌时刻"的封面（拒绝上一首）；当前完全无封面时才兜底取最新一张。
     * 已有封面则保留，等 SystemUI hook 在会话元数据变化时字节广播推送替换，避免被旧图覆盖。
     */
    private fun refreshCover(retry: Boolean) {
        val token = ++coverRefreshToken
        val packageName = activePlayerPackage() ?: run {
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

    /**
     * 自动投放决策：有歌 + 包过滤允许 + 投放主开关开 → 投屏。每次都打日志（含各门控值），
     * 便于排查"切歌/播放却没自动投"——一眼看出是主开关关了、还是被"仅监听选中应用"挡了。
     */
    private fun maybeAutoProject(reason: String) {
        val hasSong = LyricBus.song.value != null
        val allow = allowed()
        val enabled = ProjectionState.current
        Log.i(TAG, "auto-project[$reason]: song=$hasSong allowed=$allow enabled=$enabled pkg=${activePlayerPackage()} projected=${LyricBus.projected.value}")
        if (hasSong && allow && enabled) showRear()
    }

    private fun showRear() {
        if (!RootShell.available) {
            ensureRootReady("show-request")
            return
        }
        lastShowAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "showRear -> project to rear")
        projectExecutor.execute { RearProjector.show() }
    }

    private fun hideRear() {
        projectExecutor.execute { RearProjector.hide() }
    }


    override fun onDestroy() {
        super.onDestroy()
        // 退出前台：若还有别的保活服务在跑，刷新成「无歌词」态并 DETACH 让那条共享通知保留。
        _running.value = false
        ForegroundCoordinator.stopped(ForegroundCoordinator.TAG_LYRIC)
        if (ForegroundCoordinator.othersRunning(ForegroundCoordinator.TAG_LYRIC)) {
            runCatching {
                getSystemService(NotificationManager::class.java).notify(SHARED_NOTIF_ID, buildSharedNotification(this))
            }
            @Suppress("DEPRECATION") stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
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
        runCatching { SuperLyricSource.stop() }
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
        /** 歌词/工具保活共用的单一前台通知 ID。 */
        const val SHARED_NOTIF_ID = 0x21

        private val _running = MutableStateFlow(false)
        /** 歌词服务是否在前台运行（供共享通知决定文案/按钮）。 */
        val runningFlow: StateFlow<Boolean> get() = _running

        /**
         * 构建歌词/工具共用的保活通知：歌词在跑时显示「背屏歌词运行中」+投放按钮，
         * 否则显示「背屏服务运行中」(无按钮)。内容仅取全局状态，故谁来 build 都一致。
         */
        fun buildSharedNotification(context: Context): Notification {
            ServiceNotice.ensureChannel(context)
            val lyricUp = _running.value
            val projected = LyricBus.projected.value
            val enabled = ProjectionState.current
            val builder = Notification.Builder(context, ServiceNotice.CHANNEL)
                .setContentTitle("ZHITool")
                .setContentText(if (lyricUp) (if (enabled) "背屏歌词运行中" else "已停止投放") else "背屏服务运行中")
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setGroup(ServiceNotice.GROUP)
                .setOngoing(true)
            if (lyricUp) {
                val icon = Icon.createWithResource(context, R.mipmap.ic_launcher_foreground)
                builder.addAction(
                    Notification.Action.Builder(
                        icon, if (projected) "收回背屏" else "投到背屏",
                        sharedActionPending(context, CMD_TOGGLE_PROJECT, 1),
                    ).build(),
                )
                builder.addAction(
                    Notification.Action.Builder(
                        icon, if (enabled) "停止投放" else "开始投放",
                        sharedActionPending(context, CMD_TOGGLE_ENABLED, 2),
                    ).build(),
                )
            }
            return builder.build()
        }

        private fun sharedActionPending(context: Context, cmd: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(ACTION_NOTIF).setPackage(context.packageName).putExtra(EXTRA_NOTIF_CMD, cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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

        /** 自系统会话读进度的轮询间隔；以及「多久没读到会话进度就回退用词幕进度」的 TTL。 */
        private const val SESSION_POS_POLL_MS = 500L
        private const val SESSION_POS_TTL_MS = 2000L

        /** 开机后 Root 管理器可能稍晚就绪；失败后下一次歌曲/投放请求还会重新开始一轮。 */
        private val ROOT_INIT_RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        private const val ACTION_NOTIF = "com.zhitool.rearlyric.action.NOTIF_CMD"
        private const val EXTRA_NOTIF_CMD = "cmd"
        private const val CMD_TOGGLE_PROJECT = "toggle_project"
        private const val CMD_TOGGLE_ENABLED = "toggle_enabled"

        fun start(context: Context) {
            val intent = Intent(context, LyricService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LyricService::class.java))
        }
    }
}
