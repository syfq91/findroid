package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "books")
data class FindroidBookDto(
    @PrimaryKey val id: UUID,
    val serverId: String?,
    val name: String,
    val originalTitle: String?,
    val overview: String,
    val runtimeTicks: Long,
    val seriesName: String?,
    val seriesIndex: Int?,
    val authors: List<String>?,
    val genres: List<String>?,
    val communityRating: Float?,
    val productionYear: Int?,
    val container: String?,
    val size: Long?,
)

fun FindroidBook.toFindroidBookDto(serverId: String? = null): FindroidBookDto {
    return FindroidBookDto(
        id = id,
        serverId = serverId,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        runtimeTicks = runtimeTicks,
        seriesName = seriesName,
        seriesIndex = seriesIndex,
        authors = authors,
        genres = genres,
        communityRating = communityRating,
        productionYear = productionYear,
        container = container,
        size = size,
    )
}
