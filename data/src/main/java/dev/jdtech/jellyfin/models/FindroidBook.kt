package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
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
) : FindroidItem, FindroidSources

suspend fun BaseItemDto.toFindroidBook(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): FindroidBook {
    val sources = mutableListOf<FindroidSource>()
    sources.addAll(mediaSources?.map { it.toFindroidSource(jellyfinRepository, id) } ?: emptyList())
    if (serverDatabase != null) {
        sources.addAll(serverDatabase.getSources(id).map { it.toFindroidSource(serverDatabase) })
    }

    val authors =
        people
            ?.filter { it.type == "Author" || it.role?.contains("Author", ignoreCase = true) == true }
            ?.mapNotNull { it.name }
            ?.ifEmpty { null }
            ?: artistItems?.mapNotNull { it.name }
            ?: emptyList()

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
        container = container ?: mediaSources?.firstOrNull()?.container,
        size = mediaSources?.firstOrNull()?.size,
        images = toFindroidImages(jellyfinRepository),
    )
}
