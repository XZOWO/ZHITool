/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.rear

import android.content.Context
import com.zhitool.rearlyric.lyric.ConfigStore
import com.zhitool.rearlyric.lyric.LyricBus
import com.zhitool.rearlyric.lyric.ProjectionState
import com.zhitool.rearlyric.lyric.ToolProjectionState
import com.zhitool.rearlyric.tools.charge.ChargeOverlay
import com.zhitool.rearlyric.tools.notify.NotifyBus
import kotlin.concurrent.thread

/**
 * 背屏投放的统一控制（主页三键 / 通知按钮共用）。
 *
 * 主页三键即对应它们的 toggle：
 * - **歌词投到背屏 ↔ 收回歌词投屏**：投=**打开主开关并投一次**；收回=**只隐藏当前画面、主开关不变**
 *   （故收回后切歌/播放/暂停仍会自动重投，符合"收回只是临时、除非停止歌词投放才不再自动投"）。
 * - **歌词自动投放主开关** [ProjectionState]：「启动 ↔ 停止歌词投放」。开启后，播放/切歌/暂停/启动等
 *   事件由 [com.zhitool.rearlyric.lyric.LyricService] 自动把歌词投背屏。
 * - **工具投放开关** [ToolProjectionState]：充电动画 / 背屏通知是否投背屏。与歌词主开关一起由
 *   「启动 ↔ 停止所有投放」统一翻。
 */
object RearProjectionControl {

    /**
     * 投歌词到背屏：**同时打开自动投放主开关**并立即投一次。
     * 之所以连主开关一起开——用户语义里"投到背屏"=开始投放，之后切歌/播放/暂停应继续自动投；
     * 否则主开关若早先被关，单纯手动投一次后切歌就不会自动重投（表现为"投了但切歌不自动投"）。
     */
    fun showLyrics(context: Context) {
        ConfigStore.saveProjectionEnabled(context, true)
        thread(name = "zhi-project-lyric") { RearProjector.show() }
    }

    /** 收回当前歌词投放：仅隐藏画面，**不动主开关**（开关仍开→切歌/播放/暂停会自动重投）。 */
    fun retractLyrics() {
        RearProjector.hide()
    }

    /**
     * 启动/停止歌词自动投放主开关。开启时若有歌曲会由 LyricService 立即投一次；关闭时收回。
     * （状态变化由 LyricService 观察 [ProjectionState] 处理投/收。）
     */
    fun setLyricEnabled(context: Context, enabled: Boolean) {
        ConfigStore.saveProjectionEnabled(context, enabled)
        if (!enabled) RearProjector.hide()
    }

    /**
     * 启动/停止"所有投放"：歌词主开关 + 工具投放开关一起翻。
     * 关闭时收回歌词并收掉充电动画 / 通知卡片胶囊；开启时由各自的自动逻辑在下次事件投放。
     */
    fun setAllEnabled(context: Context, enabled: Boolean) {
        ConfigStore.saveProjectionEnabled(context, enabled)
        ConfigStore.saveToolProjectionEnabled(context, enabled)
        if (!enabled) {
            RearProjector.hide()
            ChargeOverlay.finish(context)
            NotifyBus.clearPill()
            NotifyBus.clearItems()
        }
    }
}
