/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * 流体填充动画移植自 MRSS (https://github.com/GoldenglowSusie, GPL-3.0) 的 LightningShapeView
 * 全屏液体模式。Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.tools.charge

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 全屏从底部上升的绿色液体（系统电池绿 #34C759），带波浪、气泡与重力倾斜。
 * 用于背屏充电动画。
 */
class LiquidFillView(context: Context) : View(context), SensorEventListener {

    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
        color = 0xFF34C759.toInt()
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x80FFFFFF.toInt()
        maskFilter = android.graphics.BlurMaskFilter(2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val liquidPath = Path()

    private var fillLevel = 0f
    private var waveOffset = 0f
    private var tiltX = 0f
    private val bubblePositions = FloatArray(6) { Math.random().toFloat() }
    private val wavePoints = FloatArray(220)
    private var lastWaveWidth = -1
    private var lastShadowHeight = -1

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var waveStartNanos = 0L

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        runCatching {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    }

    /** 设置填充比例 0..1，启动/停止波浪动画。 */
    fun setFillLevel(level: Float) {
        fillLevel = level.coerceIn(0f, 1f)
        if (fillLevel > 0.01f && frameCallback == null) startWave()
        if (fillLevel <= 0f) stopWave()
        invalidate()
    }

    private fun startWave() {
        if (frameCallback != null) return
        waveStartNanos = 0L
        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (fillLevel > 0f) {
                    if (waveStartNanos == 0L) waveStartNanos = frameTimeNanos
                    val elapsed = frameTimeNanos - waveStartNanos
                    waveOffset = ((elapsed / 1_000_000_000.0) * PI * 1.5).toFloat()
                    postInvalidateOnAnimation()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(frameCallback!!)
    }

    private fun stopWave() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fillLevel <= 0f) return
        val width = width
        val height = height
        val fillHeight = height * fillLevel
        val leftTilt = tiltX * 8
        val rightTilt = -tiltX * 8
        val waveY = height - fillHeight

        liquidPath.reset()
        liquidPath.moveTo(0f, waveY + leftTilt)
        updateWavePoints(width)
        val pointCount = minOf(width / 6, wavePoints.size).coerceAtLeast(2)
        for (i in 0 until pointCount) {
            val x = i.toFloat() / (pointCount - 1) * width
            val tilt = leftTilt + (rightTilt - leftTilt) * (x / width)
            liquidPath.lineTo(x, waveY + wavePoints[i] + tilt)
        }
        liquidPath.lineTo(width.toFloat(), height.toFloat())
        liquidPath.lineTo(0f, height.toFloat())
        liquidPath.close()
        canvas.drawPath(liquidPath, liquidPaint)

        if (lastShadowHeight != height) {
            shadowPaint.shader = LinearGradient(
                0f, height - 40f, 0f, height.toFloat(),
                intArrayOf(0x00000000, 0x20000000, 0x40000000),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
            lastShadowHeight = height
        }
        canvas.drawPath(liquidPath, shadowPaint)

        if (fillHeight > 10) drawBubbles(canvas, width, height, fillHeight)
    }

    private fun updateWavePoints(width: Int) {
        if (lastWaveWidth != width) lastWaveWidth = width
        val pointCount = minOf(width / 6, wavePoints.size).coerceAtLeast(2)
        for (i in 0 until pointCount) {
            val x = i.toFloat() / (pointCount - 1) * width
            wavePoints[i] = (sin((x / width * 4 * PI) + waveOffset) * 8f).toFloat()
        }
    }

    private fun drawBubbles(canvas: Canvas, width: Int, height: Int, fillHeight: Float) {
        val baseY = height - fillHeight
        val gx = -tiltX * 5
        val gy = tiltX * 2
        val xs = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 0.3f, 0.7f)
        val radii = floatArrayOf(6f, 4f, 3f, 3.5f, 4.5f, 2.5f)
        for (i in bubblePositions.indices) {
            val bx = width * xs[i] + gx * (1f - i * 0.08f)
            val by = baseY + fillHeight * bubblePositions[i] + gy
            canvas.drawCircle(bx, by, radii[i], bubblePaint)
            bubblePositions[i] -= 0.002f
            if (bubblePositions[i] < 0) bubblePositions[i] = 1f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager?.unregisterListener(this)
        stopWave()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val smooth = 0.05f
            tiltX = (tiltX * (1 - smooth) + x * smooth).coerceIn(-2f, 2f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
