/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 *
 * Root 直采助手：经 `su` 用 app_process 以 uid 0 运行（root 跳过 CAPTURE_AUDIO_OUTPUT/
 * MODIFY_AUDIO_ROUTING 权限检查），用 audio policy **loopback+render** 采集系统播放音频——
 * 不经 MediaProjection，故**无"正在共享屏幕"提示**；ROUTE_FLAG_LOOP_BACK|RENDER(=3) 让
 * AudioFlinger 建 DuplicatingThread（一份照常外放/蓝牙、一份回环给我们采），故**不静音**。
 *
 * 助手内做加窗 FFT，逐窗把 `V <level> <b0..b27>`（整数千分比）打到 stdout，
 * 由 App 进程的 [AudioVisualizer] 读管道、解析、喂渲染。
 * 启动：CLASSPATH=<apk> app_process /system/bin com.zhitool.rearlyric.tools.audio.RootAudioCapture
 */
package com.zhitool.rearlyric.tools.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.BufferedOutputStream
import java.io.PrintStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

object RootAudioCapture {
    private const val TAG = "ZhiRootAudio"

    private const val SAMPLE_RATE = 44100
    private const val FFT_SIZE = 1024
    private const val BAND_COUNT = 28
    private const val MAG_GAIN = 0.5f
    private const val LOG_NORM = 7.0f
    private const val LEVEL_GAIN = 1.0f

    // HPSS(谐波-打击分离,中值滤波法):时间轴中值→谐波(人声/弦乐/贝斯),频率轴中值→打击(鼓/镲/踩镲)。
    private const val HPSS_TIME = 17        // 谐波时间中值帧数（约 0.4s）
    private const val HPSS_FREQ_HALF = 8    // 打击频率中值半窗（窗宽 17 bin）
    // 汇总 bin 范围（44100/1024≈43Hz/bin）。
    private const val HARM_LO = 1      // 谐波(人声/弦乐/贝斯)汇总起始 bin（~43Hz）
    private const val HARM_HI = 256    // 谐波汇总结束 bin（~11kHz）
    private const val PERC_HI = 480    // 打击能量汇总上限 bin（含镲/踩镲）

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "start uid=${Process.myUid()} pid=${Process.myPid()}")
        runCatching { exemptHiddenApis() }.onFailure { Log.w(TAG, "exempt hidden api failed", it) }
        Looper.prepareMainLooper()

        val record = runCatching { buildLoopbackRecord() }.getOrElse {
            Log.e(TAG, "loopback setup failed, abort", it)
            return
        }

        Thread({ captureLoop(record) }, "zhi-root-cap").start()
        Looper.loop()
    }

    private fun captureLoop(rec: AudioRecord) {
        runCatching { rec.startRecording() }.onFailure { Log.e(TAG, "startRecording failed", it); return }
        Log.i(TAG, "recording state=${rec.recordingState}, streaming bands+HPSS to stdout")
        val out = PrintStream(BufferedOutputStream(System.out, 8192), false)
        val shorts = ShortArray(FFT_SIZE)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val window = FloatArray(FFT_SIZE) { 0.5f - 0.5f * cos(2.0 * PI * it / (FFT_SIZE - 1)).toFloat() } // Hann
        val half = FFT_SIZE / 2
        val mag = FloatArray(half + 1)
        val harm = FloatArray(half + 1)
        val perc = FloatArray(half + 1)
        // 时间轴中值用的滑动谱历史（环形）。
        val hist = Array(HPSS_TIME) { FloatArray(half + 1) }
        var histPos = 0
        var histFilled = 0
        val tmpT = FloatArray(HPSS_TIME)
        val tmpF = FloatArray(2 * HPSS_FREQ_HALF + 1)
        val sb = StringBuilder(320)
        while (true) {
            var off = 0
            while (off < FFT_SIZE) {
                val r = rec.read(shorts, off, FFT_SIZE - off)
                if (r > 0) off += r
                else if (r < 0) { Log.w(TAG, "read error $r"); return }
            }

            var sumSq = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = shorts[i] / 32768f
                sumSq += (s * s).toDouble()
                re[i] = s * window[i]
                im[i] = 0f
            }
            val level = (sqrt(sumSq / FFT_SIZE).toFloat() * LEVEL_GAIN).coerceIn(0f, 1f)

            Fft.transform(re, im)
            for (i in 0..half) mag[i] = sqrt(re[i] * re[i] + im[i] * im[i])

            // 写入滑动历史。
            System.arraycopy(mag, 0, hist[histPos], 0, half + 1)
            histPos = (histPos + 1) % HPSS_TIME
            if (histFilled < HPSS_TIME) histFilled++

            // 逐 bin 求谐波(时间中值)/打击(频率中值),软掩膜(Wiener,p=2)分离。
            for (i in 0..half) {
                for (k in 0 until histFilled) tmpT[k] = hist[k][i]
                val h = medianInPlace(tmpT, histFilled)
                var c = 0
                val lo = if (i - HPSS_FREQ_HALF < 0) 0 else i - HPSS_FREQ_HALF
                val hi = if (i + HPSS_FREQ_HALF > half) half else i + HPSS_FREQ_HALF
                var j = lo
                while (j <= hi) { tmpF[c++] = mag[j]; j++ }
                val p = medianInPlace(tmpF, c)
                val h2 = h * h
                val p2 = p * p
                val denom = h2 + p2 + 1e-9f
                harm[i] = mag[i] * (h2 / denom)
                perc[i] = mag[i] * (p2 / denom)
            }

            // 两团:动次打次=打击全频;人声/其它=全部谐波(贝斯+人声+弦乐,已去打击)。
            val percRaw = avg(perc, HARM_LO, PERC_HI)
            val harmRaw = avg(harm, HARM_LO, HARM_HI)

            sb.setLength(0)
            sb.append("V ").append((level * 1000f).toInt())
            // 28 段对数频谱(声谱模式用,基于原始 mag)。
            var prevEdge = 1
            for (b in 0 until BAND_COUNT) {
                val frac = (b + 1).toDouble() / BAND_COUNT
                val edge = half.toDouble().pow(frac).toInt().coerceAtLeast(prevEdge + 1).coerceAtMost(half)
                var sum = 0f
                var cnt = 0
                var i = prevEdge
                while (i < edge) { sum += mag[i]; cnt++; i++ }
                val v = normLog(if (cnt > 0) sum / cnt else 0f)
                sb.append(' ').append((v * 1000f).toInt())
                prevEdge = edge
            }
            // 两团 HPSS 能量(律动模式用):打击 / 谐波。
            sb.append(' ').append((normLog(percRaw) * 1000f).toInt())
            sb.append(' ').append((normLog(harmRaw) * 1000f).toInt())
            try {
                out.println(sb.toString())
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "stdout closed, exit"); return
            }
        }
    }

    /** 原地排序前 n 个取中值（n 视为已填）。 */
    private fun medianInPlace(a: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        java.util.Arrays.sort(a, 0, n)
        return a[n / 2]
    }

    /** bin [lo, hi) 的平均幅度。 */
    private fun avg(arr: FloatArray, lo: Int, hi: Int): Float {
        var sum = 0f
        var cnt = 0
        var i = lo
        val end = if (hi > arr.size) arr.size else hi
        while (i < end) { sum += arr[i]; cnt++; i++ }
        return if (cnt > 0) sum / cnt else 0f
    }

    /** 对数归一到 0..1。 */
    private fun normLog(x: Float): Float = (ln(1f + x * MAG_GAIN) / LOG_NORM).coerceIn(0f, 1f)

    /** 解除非 SDK 接口限制，使下面反射调用 audio policy 隐藏 API 可用。 */
    private fun exemptHiddenApis() {
        val vmRuntime = Class.forName("dalvik.system.VMRuntime")
        val runtime = vmRuntime.getDeclaredMethod("getRuntime").invoke(null)
        vmRuntime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            .invoke(runtime, arrayOf("L"))
    }

    /** app_process 无 Application，借 ActivityThread 拿系统 Context。 */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val systemMain = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(systemMain) as Context
    }

    /** audio policy loopback+render 采集（不静音、无投影）。 */
    private fun buildLoopbackRecord(): AudioRecord {
        val ctx = systemContext()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        // AudioMixingRule：匹配 MEDIA/UNKNOWN/GAME 用途的播放。
        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
        val ruleUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val addMixRule = ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN, AudioAttributes.USAGE_GAME)) {
            val attr = AudioAttributes.Builder().setUsage(usage).build()
            addMixRule.invoke(ruleBuilder, ruleUsage, attr)
        }
        val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)

        // AudioMix：LOOP_BACK|RENDER(=3) 强制 DuplicatingThread（一份外放、一份回环→不静音）。
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(rule)
        mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        val loopBack = mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
        val render = runCatching { mixCls.getField("ROUTE_FLAG_RENDER").getInt(null) }.getOrDefault(1)
        val routeFlags = loopBack or render
        Log.i(TAG, "routeFlags=$routeFlags (loopBack=$loopBack render=$render)")
        mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, routeFlags)
        val mix = mixBuilderCls.getMethod("build").invoke(mixBuilder)

        // AudioPolicy + 注册（uid 0 跳过 MODIFY_AUDIO_ROUTING 检查）。
        val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(ctx)
        policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, mix)
        val policy = policyBuilderCls.getMethod("build").invoke(policyBuilder)

        val am = ctx.getSystemService(AudioManager::class.java)
        val res = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls).invoke(am, policy) as Int
        Log.i(TAG, "registerAudioPolicy res=$res (0=SUCCESS)")
        if (res != 0) error("registerAudioPolicy failed res=$res")

        val record = policyCls.getMethod("createAudioRecordSink", mixCls).invoke(policy, mix) as AudioRecord
        Log.i(TAG, "loopback AudioRecord state=${record.state}")
        return record
    }
}
