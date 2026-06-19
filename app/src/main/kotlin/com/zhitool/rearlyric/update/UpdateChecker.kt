package com.zhitool.rearlyric.update

import com.zhitool.rearlyric.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云更新清单（仓库根的 update.json 解析结果）。
 * 比对维度是整数 [versionCode]（比解析 semver 字符串稳）。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val releaseUrl: String,
    val lyriconRequired: Boolean,
    val lyriconVersion: String,
    val lyriconApkUrl: String,
) {
    /** 清单版本号高于本机安装版本即视为有更新。 */
    val hasUpdate: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

/**
 * 应用内云更新检查。
 *
 * **主源 = GitHub Release 资源** `releases/latest/download/update.json`——它由 GitHub 重定向到
 * **最新一个 release 的附件**，永远是最新版本、**无 CDN 分支缓存**（避免 jsDelivr `@main` 的
 * 12 小时分支缓存导致刚发版却检测不到）。github 不可达时再退到 jsDelivr（国内可达，但分支缓存
 * 可能有延迟）与 raw。只做「检测 + 给下载链接」（A 档），不在应用内静默安装。
 *
 * 发版须把 `update.json` 同时作为该 release 的资源上传（与仓库根那份内容一致）。
 */
object UpdateChecker {
    private val SOURCES = listOf(
        "https://github.com/XZOWO/ZHITool/releases/latest/download/update.json",
        "https://cdn.jsdelivr.net/gh/XZOWO/ZHITool@main/update.json",
        "https://raw.githubusercontent.com/XZOWO/ZHITool/main/update.json",
    )

    @Volatile
    private var cached: UpdateInfo? = null

    /**
     * 拉取并解析清单。默认复用进程内缓存（一次启动只真正联网一次）；
     * [force] = true（手动「检查更新」）时跳过缓存重新拉。失败返回 null。
     */
    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!force) cached?.let { return@withContext it }
        for (url in SOURCES) {
            val body = fetch(url) ?: continue
            val info = parse(body) ?: continue
            cached = info
            return@withContext info
        }
        null
    }

    private fun fetch(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            // releases/latest/download/* 会 302 跳到 objects.githubusercontent.com（同为 https），
            // 默认即跟随；显式置一下更稳。
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun parse(text: String): UpdateInfo? = runCatching {
        val o = JSONObject(text)
        val ly = o.optJSONObject("lyricon")
        UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.optString("versionName"),
            changelog = o.optString("changelog"),
            apkUrl = o.optString("apkUrl"),
            releaseUrl = o.optString("releaseUrl"),
            lyriconRequired = ly?.optBoolean("required") ?: false,
            lyriconVersion = ly?.optString("versionName").orEmpty(),
            lyriconApkUrl = ly?.optString("apkUrl").orEmpty(),
        )
    }.getOrNull()
}
