package com.zhitool.rearlyric.lyric

import android.content.Context
import com.zhitool.rearlyric.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * 词幕式封面缓存目录。
 *
 * 封面由 SystemUI 作用域中的 Xposed 代码写入模块自身数据目录，应用进程再从相同路径读取，
 * 从而避免依赖通知使用权。
 */
object CoverStorage {
    private const val ROOT_DIR = "cover-cache"
    private const val PACKAGES_DIR = "packages"
    private const val CACHES_DIR = "caches"
    private const val LATEST_FILE = "cover.png"

    /**
     * SystemUI hook 写完封面后，向 app 进程发的实时广播。
     * 跨进程 FileObserver 在部分 ROM 上不可靠（封面滞后/留旧），改用显式广播即时通知。
     */
    const val ACTION_COVER_UPDATED = "com.zhitool.rearlyric.action.COVER_UPDATED"
    /** app 冷启动/切播放器时请求当前封面，hook（SystemUI）回送最新字节。 */
    const val ACTION_COVER_REQUEST = "com.zhitool.rearlyric.action.COVER_REQUEST"
    const val EXTRA_PACKAGE = "package"
    /** 封面 PNG 字节（hook 直接随广播携带，绕开文件 IPC）。 */
    const val EXTRA_COVER = "cover"

    fun latestCoverFile(context: Context, packageName: String): File =
        File(packageDir(context, packageName), LATEST_FILE)

    fun cachedCoverFile(
        context: Context,
        packageName: String,
        title: String,
        artist: String,
    ): File = File(packageDir(context, packageName), "$CACHES_DIR/${cacheKey(title, artist)}.png")

    fun packageDir(context: Context, packageName: String): File =
        File(File(rootDir(context), PACKAGES_DIR), packageName).apply { mkdirs() }

    fun rootDir(context: Context): File =
        File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    fun moduleContext(hostContext: Context): Context? = runCatching {
        hostContext.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()

    private fun cacheKey(title: String, artist: String): String =
        md5("${title}_$artist")

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }
}
