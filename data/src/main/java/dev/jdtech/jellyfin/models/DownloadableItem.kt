package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File

/**
 * A [FindroidItem] that can be downloaded for offline playback.
 *
 * The downloader dispatches on this interface instead of type-checking each concrete item, so
 * adding a new downloadable item type means implementing it here rather than editing the
 * downloader. Movies and episodes additionally download video extras — media segments, trickplay
 * thumbnails, and external media streams — that books, being page-based, never have.
 */
interface DownloadableItem : FindroidItem {
    /**
     * Whether this item downloads video extras: media segments, trickplay thumbnails, and external
     * media streams. False for books, which have none of these.
     */
    val hasVideoExtras: Boolean

    /**
     * Resolves the source to download for [sourceId], falling back gracefully when the server
     * cannot describe it. Books resolve to their download URL; movies and episodes require a
     * matching server-reported source.
     */
    suspend fun resolveDownloadSource(
        sourceId: String,
        repository: JellyfinRepository,
    ): FindroidSource

    /**
     * Persists this item's download metadata to [database] so it is available offline. For an
     * episode this also persists its show and season. [serverId] identifies the server the item
     * came from, for multi-server databases.
     */
    suspend fun persistDownloadMetadata(
        database: ServerDatabaseDao,
        repository: JellyfinRepository,
        serverId: String?,
    )

    /**
     * Deletes this item's download metadata from [database] and removes any files it owns under
     * [filesDir]. For an episode this cascades to its season and show once they have no remaining
     * episodes.
     */
    suspend fun deleteDownloadMetadata(
        database: ServerDatabaseDao,
        filesDir: File,
    )
}
