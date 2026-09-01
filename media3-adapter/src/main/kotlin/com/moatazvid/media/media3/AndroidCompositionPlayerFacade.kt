package com.moatazvid.media.media3

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.moatazvid.core.TimeUs
import com.moatazvid.media.PreviewSurface
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Concrete CompositionPlayer binding shared with the same Composition factory as final export. */
@OptIn(UnstableApi::class)
class AndroidCompositionPlayerFacade(
    private val context: Context,
    private val compositionFactory: Media3RuntimeCompositionFactory,
) : CompositionPlayerFacade {
    private val sessions = ConcurrentHashMap<String, CompositionPlayer>()

    override suspend fun prepare(sessionId: String, composition: Media3CompositionSpec, surface: PreviewSurface) =
        withContext(Dispatchers.Main.immediate) {
            require(sessionId.isNotBlank())
            sessions.remove(sessionId)?.release()
            val player = CompositionPlayer.Builder(context).build()
            attachSurface(player, surface.token)
            player.setComposition(compositionFactory.build(composition))
            player.prepare()
            sessions[sessionId] = player
        }

    override suspend fun replace(sessionId: String, composition: Media3CompositionSpec) =
        withContext(Dispatchers.Main.immediate) {
            val player = requireNotNull(sessions[sessionId]) { "Unknown preview session $sessionId" }
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val wasPlaying = player.playWhenReady
            player.setComposition(compositionFactory.build(composition), positionMs)
            player.prepare()
            player.playWhenReady = wasPlaying
        }

    override suspend fun seek(sessionId: String, position: TimeUs) = withContext(Dispatchers.Main.immediate) {
        val player = requireNotNull(sessions[sessionId]) { "Unknown preview session $sessionId" }
        player.seekTo(position.value / 1_000L)
    }

    override suspend fun release(sessionId: String) = withContext(Dispatchers.Main.immediate) {
        sessions.remove(sessionId)?.release()
    }

    private fun attachSurface(player: CompositionPlayer, token: Any) {
        when (token) {
            is Surface -> player.setVideoSurface(token)
            is SurfaceHolder -> player.setVideoSurfaceHolder(token)
            is SurfaceView -> player.setVideoSurfaceView(token)
            is TextureView -> player.setVideoTextureView(token)
            else -> error("Unsupported preview surface token ${token.javaClass.name}")
        }
    }
}
