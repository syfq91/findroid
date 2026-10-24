package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.PlayAccess

data class FindroidBook(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val seriesName: String?,
    val seriesIndex: Int?,
    val authors: List<String>,
    val genres: List<String>,
    val communityRating: Float?,
    val productionYear: Int?,
    val container: String?,
    val size: Long?,
    override val unplayedItemCount: Int? = null,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
    override val trickplayInfo: Map<String, FindroidTrickplayInfo>? = null,
) : FindroidItem, FindroidSources, DownloadableItem {
    override val hasVideoExtras: Boolean = false

    override suspend fun resolveDownloadSource(
        sourceId: String,
        repository: JellyfinRepository,
    ): FindroidSource {
        val rawSource =
            sources.firstOrNull { it.id == sourceId }
                ?: try {
                    repository.getMediaSources(id, true).firstOrNull { it.id == sourceId }
                } catch (_: Exception) {
                    null
                }
                ?: sources.firstOrNull()
                ?: FindroidSource(
                    id = sourceId.ifBlank { id.toString() },
                    name = name,
                    type = FindroidSourceType.REMOTE,
                    path = repository.getDownloadUrl(id),
                    size = size ?: 0L,
                    mediaStreams = emptyList(),
                )
        return if (rawSource.path.isBlank() || !rawSource.path.startsWith("http")) {
            rawSource.copy(path = repository.getDownloadUrl(id))
        } else {
            rawSource
        }
    }

    override suspend fun persistDownloadMetadata(
        database: ServerDatabaseDao,
        repository: JellyfinRepository,
        serverId: String?,
    ) {
        database.insertBook(toFindroidBookDto(serverId))
    }

    override suspend fun deleteDownloadMetadata(database: ServerDatabaseDao, filesDir: File) {
        database.deleteBook(id)
    }
}

suspend fun BaseItemDto.toFindroidBook(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): FindroidBook {
    val sources = mutableListOf<FindroidSource>()
    sources.addAll(
        mediaSources?.map {
            val src = it.toFindroidSource(jellyfinRepository, id)
            if (src.path.isBlank() || !src.path.startsWith("http")) {
                src.copy(path = jellyfinRepository.getDownloadUrl(id))
            } else {
                src
            }
        } ?: emptyList()
    )
    if (serverDatabase != null) {
        sources.addAll(serverDatabase.getSources(id).map { it.toFindroidSource(serverDatabase) })
    }
    if (sources.none { it.type == FindroidSourceType.REMOTE }) {
        sources.add(
            FindroidSource(
                id = id.toString(),
                name = name.orEmpty(),
                type = FindroidSourceType.REMOTE,
                path = jellyfinRepository.getDownloadUrl(id),
                size = mediaSources?.firstOrNull()?.size ?: 0L,
                mediaStreams = emptyList(),
            )
        )
    }

    val authors =
        people
            ?.filter {
                it.type == PersonKind.AUTHOR ||
                    it.role?.contains("Author", ignoreCase = true) == true
            }
            ?.mapNotNull { it.name }
            ?.ifEmpty { null } ?: artistItems?.mapNotNull { it.name } ?: emptyList()

    return FindroidBook(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        sources = sources,
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        runtimeTicks = runTimeTicks ?: 0,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        seriesName = seriesName,
        seriesIndex = indexNumber,
        authors = authors,
        genres = genres ?: emptyList(),
        communityRating = communityRating,
        productionYear = productionYear,
        container =
            container?.takeIf { it.isNotBlank() }
                ?: mediaSources?.firstOrNull()?.container?.takeIf { it.isNotBlank() }
                ?: mediaSources?.firstOrNull()?.path?.substringAfterLast('.', "")?.takeIf {
                    it.isNotBlank()
                }
                ?: path?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() },
        size = mediaSources?.firstOrNull()?.size,
        images = toFindroidImages(jellyfinRepository),
    )
}

suspend fun FindroidBookDto.toFindroidBook(
    database: ServerDatabaseDao,
    userId: UUID,
): FindroidBook {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = database.getSources(id).map { it.toFindroidSource(database) }
    return FindroidBook(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        sources = sources,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        seriesName = seriesName,
        seriesIndex = seriesIndex,
        authors = authors ?: emptyList(),
        genres = genres ?: emptyList(),
        communityRating = communityRating,
        productionYear = productionYear,
        container = container,
        size = size,
        images = toLocalFindroidImages(itemId = id),
    )
}
