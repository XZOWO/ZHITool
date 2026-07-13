/*
 * This file is part of ZHITool — licensed under GPL-3.0 (see LICENSE).
 * Copyright (C) 2026 ZHITool authors.
 */
package com.zhitool.rearlyric.core

/**
 * ZHITool app process and the LSPosed-injected SystemUI process exchange only primitive
 * broadcast extras. Keeping the protocol here avoids passing framework parcelables across
 * different process/class-loader boundaries.
 */
object MediaSessionBridge {
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val ACTION_SNAPSHOT = "com.zhitool.rearlyric.action.MEDIA_SESSION_SNAPSHOT"
    const val ACTION_REQUEST = "com.zhitool.rearlyric.action.MEDIA_SESSION_REQUEST"
    const val ACTION_COMMAND = "com.zhitool.rearlyric.action.MEDIA_SESSION_COMMAND"

    const val EXTRA_PACKAGE = "package"
    const val EXTRA_AVAILABLE = "available"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_PLAYBACK_STATE = "playback_state"
    const val EXTRA_POSITION = "position"
    const val EXTRA_POSITION_UPDATED_AT = "position_updated_at"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_CAN_FAVORITE = "can_favorite"
    const val EXTRA_IS_FAVORITED = "is_favorited"
    const val EXTRA_COMMAND = "command"

    const val COMMAND_PLAY_PAUSE = "play_pause"
    const val COMMAND_NEXT = "next"
    const val COMMAND_PREVIOUS = "previous"
    const val COMMAND_SEEK_TO = "seek_to"
    const val COMMAND_FAVORITE = "favorite"
}
