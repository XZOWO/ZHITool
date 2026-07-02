/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.audio

import android.content.Context
import android.util.Log
import com.zhitool.rearlyric.core.RootShell
import kotlin.concurrent.thread

/**
 * 音频可视化数据源（律动/声谱用）。
 *
 * **音频通路**：经 `su` 用 app_process 以 uid 0 跑 [RootAudioCapture]，用 audio policy
 * **loopback+render** 直采系统播放音频——能拿到 offload 音乐、**不静音**(DuplicatingThread
 * 一份照常外放/蓝牙)、且**不经 MediaProjection→无"正在共享屏幕"提示**。助手内做 FFT，
 * 逐窗把 `V <level> <b0..b27>`（整数千分比）打到 stdout；本对象读管道、解析成 [level]/[bands]，
 * 渲染帧循环读取再做攻击/衰减平滑。
 */
object AudioVisualizer {
    private const val TAG = "ZhiRootAudio"

    /** 频段数量（对数分布，给声谱柱用）。 */
    const val BAND_COUNT = 28

    /** 整体能量 0..1（PCM RMS）；由读管道线程更新，渲染线程读取。 */
    @Volatile
    var level: Float = 0f
        private set

    /** 各频段强度 0..1（对数分布）；整数组原子替换，读取安全。 */
    @Volatile
    var bands: FloatArray = FloatArray(BAND_COUNT)
        private set

    /** HPSS 分离后的两团能量 0..1：[0]=打击(动次打次)、[1]=谐波(人声/其它)。律动模式用。 */
    @Volatile
    var groups: FloatArray = FloatArray(2)
        private set

    private val lock = Any()
    @Volatile
    private var running = false
    private var process: Process? = null
    /** 引用计数：背景律动/歌词发光+控件律动可能同时独立请求内录，任一还在用就不能停。 */
    private var refCount = 0

    /** 请求开始内录（可重入）：拉起 root 助手进程、读其 stdout；已在跑则只加引用计数。 */
    fun startCapture(context: Context) {
        synchronized(lock) {
            refCount++
            if (running) return
            running = true
        }
        val app = context.applicationContext
        thread(name = "zhi-rootaudio", isDaemon = true) { runHelper(app) }
    }

    /** 释放一次引用；引用计数归零才真正杀 root 助手（其退出后 audio policy 自动注销、音频恢复）。 */
    fun stopCapture(@Suppress("UNUSED_PARAMETER") context: Context) {
        synchronized(lock) {
            refCount = (refCount - 1).coerceAtLeast(0)
            if (refCount > 0) return
            running = false
        }
        runCatching { process?.destroy() }
        process = null
        // 进程树经 su 可能不被 destroy 直接杀到，按唯一类名兜底 pkill。
        runCatching { RootShell.run("pkill -f RootAudioCapture") }
        reset()
    }

    private fun runHelper(context: Context) {
        val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull()
        if (apk.isNullOrBlank()) {
            Log.w(TAG, "apk path null")
            synchronized(lock) { running = false }
            return
        }
        runCatching { RootShell.run("pkill -f RootAudioCapture") } // 清理残留
        val cls = "com.zhitool.rearlyric.tools.audio.RootAudioCapture"
        val p = runCatching {
            ProcessBuilder("su", "-c", "CLASSPATH=$apk exec app_process /system/bin $cls")
                .redirectErrorStream(false)
                .start()
        }.getOrElse {
            Log.w(TAG, "spawn root helper failed", it)
            synchronized(lock) { running = false }
            return
        }
        process = p
        try {
            val reader = p.inputStream.bufferedReader()
            while (running) {
                val line = reader.readLine() ?: break
                parseLine(line)
            }
        } catch (_: Throwable) {
        } finally {
            reset()
            synchronized(lock) { running = false }
        }
    }

    /** 解析助手一行 `V <level> <b0..b27> <打击> <谐波>`（整数千分比）。 */
    private fun parseLine(line: String) {
        if (line.length < 2 || line[0] != 'V' || line[1] != ' ') return
        val parts = line.split(' ')
        if (parts.size < 2 + BAND_COUNT + 2) return // "V" + level + 28 bands + 2 群
        val lvl = parts[1].toIntOrNull() ?: return
        val b = FloatArray(BAND_COUNT) { (parts[it + 2].toIntOrNull() ?: 0) / 1000f }
        val base = 2 + BAND_COUNT
        val gr = FloatArray(2) { (parts[base + it].toIntOrNull() ?: 0) / 1000f }
        level = (lvl / 1000f).coerceIn(0f, 1f)
        bands = b
        groups = gr
    }

    private fun reset() {
        level = 0f
        bands = FloatArray(BAND_COUNT)
        groups = FloatArray(2)
    }
}
