package com.zhitool.rearlyric.lyric

import android.os.SystemClock
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程内歌词状态总线。
 *
 * [LyricService] 作为 lyricon 订阅端写入；[com.zhitool.rearlyric.rear.RearLyricActivity]
 * 在背屏进程内读取渲染。二者同进程，直接共享对象即可，无需 IPC。
 */
object LyricBus {

    /** 当前歌曲（含逐字富歌词）。 */
    val song = MutableStateFlow<Song?>(null)

    /** 当前播放进度（毫秒），由订阅端按 position/seek/插值刷新。 */
    val position = MutableStateFlow(0L)

    /** 是否正在播放。 */
    val playing = MutableStateFlow(false)

    /** 当前封面（用于背屏取色 / 封面位展示）。 */
    val cover = MutableStateFlow<ByteArray?>(null)

    /**
     * 设置封面，内容相同则跳过——避免 QQ 音乐逐句重推同一张图导致旋转封面角度归零重置、
     * 以及无谓的重复解码/取色。
     */
    fun setCover(bytes: ByteArray?) {
        val cur = cover.value
        if (bytes === cur) return
        if (bytes != null && cur != null && bytes.contentEquals(cur)) return
        cover.value = bytes
    }

    /** 当前活跃播放器包名。 */
    val playerPackage = MutableStateFlow<String?>(null)

    /** 背屏歌词页是否处于投放状态（背屏 Activity 据此自行关闭）。 */
    val projected = MutableStateFlow(false)

    /**
     * 背屏 Activity 自报的 taskId（-1 = 未知）。
     * 投影器优先用它移屏，避免解析 `am stack list` 失败时任务滞留主屏。
     */
    val rearTaskId = MutableStateFlow(-1)

    /**
     * 背屏渲染帧心跳（elapsedRealtime）。息屏导致 Activity 被 stop 时 Compose
     * 帧时钟暂停、心跳停更，服务侧据此检测"冻结"并重新拉起投屏。
     */
    @Volatile
    var lastFrameAt = 0L
        private set

    /** 渲染端每帧调用，喂心跳。 */
    fun markFrame() {
        lastFrameAt = SystemClock.elapsedRealtime()
    }

    val songFlow: StateFlow<Song?> get() = song
    val positionFlow: StateFlow<Long> get() = position
    val playingFlow: StateFlow<Boolean> get() = playing

    @Volatile
    private var positionAtUptime = 0L

    /** 写入新的播放进度，并记录系统时钟以便渲染端插值。 */
    fun setPosition(pos: Long) {
        position.value = pos
        positionAtUptime = SystemClock.elapsedRealtime()
    }

    /** 渲染端取「当前」插值进度：播放中按已流逝时间外推，暂停则用最后值。 */
    fun currentPosition(): Long {
        val base = position.value
        return if (playing.value) base + (SystemClock.elapsedRealtime() - positionAtUptime) else base
    }

    fun reset() {
        song.value = null
        position.value = 0L
        positionAtUptime = SystemClock.elapsedRealtime()
        playing.value = false
        cover.value = null
        playerPackage.value = null
    }
}
