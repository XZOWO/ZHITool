/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * root shell approach参考 MRSS (https://github.com/GoldenglowSusie, GPL-3.0)
 * 与 REAREye (https://github.com/killerprojecte/REAREye, GPL-3.0) 的 RootHelper。
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Root 检测 / 执行助手（兼容 Magisk / KernelSU / APatch）。
 *
 * 复用**同一个长驻 su 会话**：开一次 `su`，后续命令往同一 stdin 写、用唯一标记读回结果，
 * 避免每条命令都 `fork` 一个新 su 进程——保活循环(每 500ms 一次 WAKEUP)高频调用，
 * 持久会话显著减少 CPU/耗电。stderr 后台抽干，防止管道写满阻塞读取。
 * 所有命令经一把锁串行化；会话意外断开时下次调用自动重建。
 */
object RootShell {
    private const val TAG = "ZhiRootShell"

    @Volatile
    private var cachedAvailable: Boolean? = null

    /** 非阻塞读取缓存的 root 可用状态（未检测过为 false）。 */
    val available: Boolean
        get() = cachedAvailable == true

    private val lock = Any()
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var stdout: BufferedReader? = null

    /** 阻塞式检测 root（首次会触发授权弹窗）。务必在后台线程调用。 */
    fun refresh(): Boolean {
        val ok = exec("id").output.contains("uid=0")
        cachedAvailable = ok
        Log.i(TAG, "root refresh -> $ok")
        return ok
    }

    data class Result(val exitCode: Int, val output: String) {
        val isSuccess get() = exitCode == 0
    }

    /** 以 root 执行单条命令并返回退出码 + 标准输出（复用长驻 su 会话）。 */
    fun exec(command: String): Result = synchronized(lock) {
        if (!ensureSession()) return@synchronized Result(-1, "")
        val os = stdin ?: return@synchronized Result(-1, "")
        val reader = stdout ?: return@synchronized Result(-1, "")
        // 唯一结束标记：命令执行完紧跟一行「标记+退出码」，据此切分本次命令的输出与退出码。
        val marker = "__ZHI_${System.nanoTime()}__"
        try {
            os.write("$command\n".toByteArray())
            os.write("echo $marker$?\n".toByteArray())
            os.flush()
            val sb = StringBuilder()
            var code = -1
            while (true) {
                val line = reader.readLine() ?: run {
                    closeSession() // 会话断开
                    return@synchronized Result(-1, sb.toString().trim())
                }
                val idx = line.indexOf(marker)
                if (idx >= 0) {
                    if (idx > 0) sb.append(line, 0, idx) // 标记行前的残余输出（命令末尾无换行时）
                    code = line.substring(idx + marker.length).trim().toIntOrNull() ?: -1
                    break
                }
                sb.appendLine(line)
            }
            Result(code, sb.toString().trim())
        } catch (t: Throwable) {
            Log.w(TAG, "root exec failed: $command -> ${t.message}")
            closeSession()
            Result(-1, "")
        }
    }

    /** 执行命令仅关心是否成功。 */
    fun run(command: String): Boolean = exec(command).isSuccess

    /** 确保长驻 su 会话存活，必要时重建（仅在持锁状态下调用）。 */
    private fun ensureSession(): Boolean {
        process?.let { if (it.isAlive) return true }
        closeSession()
        return try {
            val np = Runtime.getRuntime().exec("su")
            // 后台抽干 stderr，避免管道写满阻塞 readLine。
            Thread {
                runCatching { np.errorStream.bufferedReader().forEachLine { /* discard */ } }
            }.apply { isDaemon = true }.start()
            process = np
            stdin = np.outputStream
            stdout = BufferedReader(InputStreamReader(np.inputStream))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "spawn su failed: ${t.message}")
            closeSession()
            false
        }
    }

    private fun closeSession() {
        runCatching { stdin?.close() }
        runCatching { stdout?.close() }
        runCatching { process?.destroy() }
        stdin = null
        stdout = null
        process = null
    }
}
