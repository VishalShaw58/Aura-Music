package com.own.bitchord

import android.media.audiofx.Equalizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Shared handle so the UI can reach the engine (same process). */
object Engine { var player: ExoPlayer? = null; var eq: Equalizer? = null }

/** Foreground media service: keeps playing in background, shows notification and lock-screen controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        Engine.player = player
        Engine.eq = runCatching { Equalizer(0, player.audioSessionId).apply { enabled = true } }.getOrNull()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        Engine.eq?.release(); Engine.eq = null; Engine.player = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
