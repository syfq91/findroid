package dev.jdtech.jellyfin.player.local.presentation

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType

/**
 * Builds the ExoPlayer used by [PlayerViewModel].
 *
 * Kept out of the ViewModel so the wiring is unit-testable in isolation. The libass wiring matches
 * the library's own `buildWithAssSupport` recipe: an ASS-aware extractors factory, a subtitle
 * parser factory, ASS-aware renderers, and an initialised [AssHandler].
 */
internal object ExoPlayerFactory {
    /**
     * Creates an ExoPlayer.
     *
     * @return the built player paired with its [AssHandler] when [useLibass] is true, or null when
     *   libass is disabled.
     */
    fun create(
        context: Context,
        useLibass: Boolean,
        audioAttributes: AudioAttributes,
        trackSelector: TrackSelector,
        seekBackMs: Long,
        seekForwardMs: Long,
    ): Pair<ExoPlayer, AssHandler?> {
        val renderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val builder =
            ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, true)
                .setTrackSelector(trackSelector)
                .setSeekBackIncrementMs(seekBackMs)
                .setSeekForwardIncrementMs(seekForwardMs)
                .setPauseAtEndOfMediaItems(true)

        if (!useLibass) {
            return builder.setRenderersFactory(renderersFactory).build() to null
        }

        val handler =
            AssHandler(renderType = AssRenderType.OVERLAY_OPEN_GL, config = AssHandlerConfig())
        val subtitleParserFactory = AssSubtitleParserFactory(handler)
        val extractorsFactory =
            DefaultExtractorsFactory().withAssMkvSupport(subtitleParserFactory, handler)
        val mediaSourceFactory =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context), extractorsFactory)
                .setSubtitleParserFactory(subtitleParserFactory)

        builder
            .setRenderersFactory(renderersFactory.withAssSupport(handler))
            .setMediaSourceFactory(mediaSourceFactory)

        val player = builder.build()
        handler.init(player)
        return player to handler
    }
}
