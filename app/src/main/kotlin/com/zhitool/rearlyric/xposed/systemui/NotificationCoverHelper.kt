/*
 * This file is part of ZHITool — the project as a whole is licensed under GPL-3.0 (see LICENSE).
 * Derived from lyricon (https://github.com/tomakino/lyricon), Copyright 2026 Proify/Tomakino,
 * originally licensed under the Apache License 2.0; modified by the ZHITool authors.
 * The original Apache-2.0 attribution is retained in NOTICE.
 */
package com.zhitool.rearlyric.xposed.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import com.zhitool.rearlyric.BuildConfig
import com.zhitool.rearlyric.lyric.CoverStorage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直接移植词幕的 SystemUI 会话封面提取思路：
 * 在 SystemUI 侧监听所有活跃媒体会话，把封面保存到模块私有目录，应用进程再从同一路径读取。
 */
object NotificationCoverHelper {
    private const val TAG = "ZhiCoverHelper"
    private const val TEMP_FILENAME = "cover.png.tmp"
    private const val MAX_COVER_SIZE = 128

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionWriters = ConcurrentHashMap<String, SessionCoverWriter>()
    private val latestMetadata = ConcurrentHashMap<String, MediaMetadata>()

    /** 各包最新封面 PNG 字节，用于 app 冷启动请求时立即回送（无需等下次切歌）。 */
    private val latestBytes = ConcurrentHashMap<String, ByteArray>()

    /** SystemUI 宿主 context，用于封面写完后向 app 进程发实时广播。 */
    @Volatile
    private var hostContext: Context? = null

    /** app 冷启动/切播放器时请求当前封面，hook 回送缓存的最新字节。 */
    private val coverRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CoverStorage.ACTION_COVER_REQUEST) return
            val pkg = intent.getStringExtra(CoverStorage.EXTRA_PACKAGE) ?: return
            latestBytes[pkg]?.let { notifyAppCoverUpdated(pkg, it) }
        }
    }

    fun initialize(context: Context) {
        hostContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                coverRequestReceiver,
                IntentFilter(CoverStorage.ACTION_COVER_REQUEST),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "register cover request receiver failed", it) }
        SystemUIMediaUtils.registerListener(object : SystemUIMediaUtils.MediaControllerCallback {
            override fun onMediaChanged(controller: MediaController, metadata: MediaMetadata) {
                val packageName = controller.packageName ?: return
                latestMetadata[packageName] = metadata
                getOrCreateWriter(packageName).onMetadataChanged(metadata)
            }

            override fun onPlaybackStateChanged(controller: MediaController, state: android.media.session.PlaybackState) {
                val packageName = controller.packageName ?: return
                val metadata = controller.metadata ?: latestMetadata[packageName] ?: return
                latestMetadata[packageName] = metadata
                getOrCreateWriter(packageName).onMetadataChanged(metadata)
            }

            override fun onSessionDestroyed(controller: MediaController) {
                val packageName = controller.packageName ?: return
                latestMetadata.remove(packageName)
                sessionWriters.remove(packageName)
            }
        })
    }

    fun destroy() {
        scope.cancel()
        sessionWriters.clear()
    }

    @Synchronized
    private fun getOrCreateWriter(packageName: String): SessionCoverWriter =
        sessionWriters.getOrPut(packageName) { SessionCoverWriter(packageName) }

    /** 把封面字节直接显式广播给 app（绕开文件，规避 SELinux）。 */
    private fun notifyAppCoverUpdated(packageName: String, bytes: ByteArray) {
        val ctx = hostContext ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(CoverStorage.ACTION_COVER_UPDATED)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(CoverStorage.EXTRA_PACKAGE, packageName)
                    .putExtra(CoverStorage.EXTRA_COVER, bytes)
            )
        }.onFailure { Log.w(TAG, "send cover broadcast failed", it) }
    }

    private class SessionCoverWriter(private val packageName: String) {
        @Volatile
        private var lastCoverId = -1

        @Volatile
        private var lastMetadataKey: String? = null

        private val writeMutex = Mutex()

        fun onMetadataChanged(metadata: MediaMetadata) {
            val originalCover = extractBitmap(metadata) ?: return
            if (originalCover.isRecycled) return
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val coverId = originalCover.generationId
            val metadataKey = buildMetadataKey(coverId, title, artist)
            if (lastMetadataKey == metadataKey) return
            val scaledCover = cloneAndScaleCover(originalCover) ?: return
            scope.launch {
                saveCoverAndCache(scaledCover, coverId, metadataKey, title, artist)
            }
        }

        private fun cloneAndScaleCover(original: Bitmap): Bitmap? = try {
            if (original.width <= MAX_COVER_SIZE && original.height <= MAX_COVER_SIZE) {
                original.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                original.scale(MAX_COVER_SIZE, MAX_COVER_SIZE)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "clone cover failed for $packageName", t)
            null
        }

        private suspend fun saveCoverAndCache(
            cover: Bitmap,
            coverId: Int,
            metadataKey: String,
            title: String,
            artist: String,
        ) {
            try {
                writeMutex.withLock {
                    if (lastMetadataKey == metadataKey) {
                        cover.safeRecycle()
                        return@withLock
                    }

                    val bytes = withContext(Dispatchers.IO) { cover.toPngBytes() }
                    cover.safeRecycle()
                    if (bytes != null) {
                        lastCoverId = coverId
                        lastMetadataKey = metadataKey
                        latestBytes[packageName] = bytes
                        // 直接广播封面字节给 app（绕开文件写入，规避 SELinux 拦 SystemUI 写本应用目录）。
                        notifyAppCoverUpdated(packageName, bytes)
                        // 顺带尝试写文件缓存（成功最好，失败无妨：app 已从广播拿到字节）。
                        withContext(Dispatchers.IO) { writeBytesToDisk(bytes, title, artist) }
                    }
                }
            } catch (e: CancellationException) {
                cover.safeRecycle()
                throw e
            }
        }

        private fun writeBytesToDisk(bytes: ByteArray, title: String, artist: String): Boolean {
            return try {
                val dataDir = Directory.getPackageDataDir(packageName) ?: return false
                val targetFile = java.io.File(dataDir, "cover.png")
                val tempFile = java.io.File(dataDir, TEMP_FILENAME)

                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(bytes)

                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )

                if (title.isNotBlank() || artist.isNotBlank()) {
                    val cacheFile = java.io.File(dataDir, "caches/${cacheKey(title, artist)}.png")
                    cacheFile.parentFile?.mkdirs()
                    targetFile.copyTo(cacheFile, overwrite = true)
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "save cover failed for $packageName", t)
                false
            }
        }

        private fun cacheKey(title: String, artist: String): String = md5("${title}_$artist")

        private fun buildMetadataKey(coverId: Int, title: String, artist: String): String =
            "$coverId|$title|$artist"
    }

    private fun extractBitmap(metadata: MediaMetadata): Bitmap? =
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun Bitmap.toPngBytes(): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            if (compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    }.getOrNull()

    private fun Bitmap.safeRecycle() {
        if (!isRecycled) recycle()
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }
}
