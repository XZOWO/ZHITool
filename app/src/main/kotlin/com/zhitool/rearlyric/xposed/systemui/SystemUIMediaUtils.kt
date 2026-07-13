/*
 * This file is part of ZHITool — the project as a whole is licensed under GPL-3.0 (see LICENSE).
 * Derived from lyricon (https://github.com/tomakino/lyricon), Copyright 2026 Proify/Tomakino,
 * originally licensed under the Apache License 2.0; modified by the ZHITool authors.
 * The original Apache-2.0 attribution is retained in NOTICE.
 */
package com.zhitool.rearlyric.xposed.systemui

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object SystemUIMediaUtils {
    private const val TAG = "ZhiSystemUIMedia"

    private val activeSessions = ConcurrentHashMap<MediaSession.Token, ControllerWrapper>()
    private val listeners = CopyOnWriteArraySet<MediaControllerCallback>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateCallbackRegistrations(controllers)
    }

    fun init(context: Context) {
        if (mediaSessionManager != null) return
        val manager = context.applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? MediaSessionManager
        mediaSessionManager = manager
        try {
            manager?.addOnActiveSessionsChangedListener(sessionListener, null)
            updateCallbackRegistrations(manager?.getActiveSessions(null))
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
        }
    }

    private fun updateCallbackRegistrations(controllers: List<MediaController>?) {
        val newTokens = controllers?.map { it.sessionToken }?.toSet().orEmpty()

        val iterator = activeSessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in newTokens) {
                entry.value.release()
                iterator.remove()
                listeners.forEach { it.onSessionDestroyed(entry.value.controller) }
            }
        }

        controllers?.forEach { controller ->
            val token = controller.sessionToken
            if (activeSessions.containsKey(token)) return@forEach
            val wrapper = ControllerWrapper(controller)
            wrapper.register()
            activeSessions[token] = wrapper
            dispatchInitialState(controller)
        }
    }

    private fun dispatchInitialState(controller: MediaController) {
        val metadata = controller.metadata
        val state = controller.playbackState
        mainHandler.post {
            listeners.forEach { listener ->
                metadata?.let { listener.onMediaChanged(controller, it) }
                state?.let { listener.onPlaybackStateChanged(controller, it) }
            }
        }
    }

    fun registerListener(listener: MediaControllerCallback): Boolean = listeners.add(listener)

    fun unregisterListener(listener: MediaControllerCallback): Boolean = listeners.remove(listener)

    fun controllers(): List<MediaController> = activeSessions.values.map { it.controller }

    /** Exact package first; with no package prefer the currently playing session. */
    fun controllerFor(packageName: String?): MediaController? {
        val controllers = controllers()
        return controllers.firstOrNull { it.packageName == packageName }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
    }

    private class ControllerWrapper(val controller: MediaController) {
        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                metadata ?: return
                mainHandler.post {
                    listeners.forEach { it.onMediaChanged(controller, metadata) }
                }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                state ?: return
                mainHandler.post {
                    listeners.forEach { it.onPlaybackStateChanged(controller, state) }
                }
            }

            override fun onSessionDestroyed() {
                mainHandler.post {
                    activeSessions.remove(controller.sessionToken)?.release()
                    listeners.forEach { it.onSessionDestroyed(controller) }
                }
            }
        }

        fun register() {
            runCatching { controller.registerCallback(callback, mainHandler) }
                .onFailure { Log.e(TAG, "register callback failed", it) }
        }

        fun release() {
            runCatching { controller.unregisterCallback(callback) }
                .onFailure { Log.w(TAG, "unregister callback failed", it) }
        }
    }

    interface MediaControllerCallback {
        fun onMediaChanged(controller: MediaController, metadata: MediaMetadata)
        fun onPlaybackStateChanged(controller: MediaController, state: PlaybackState) {}
        fun onSessionDestroyed(controller: MediaController) {}
    }
}
