package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadableItem
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toFindroidMediaStreamDto
import dev.jdtech.jellyfin.models.toFindroidSegmentsDto
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.models.toFindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.toFindroidUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.ImagesDownloaderWorker
import java.io.File
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): Pair<Long, UiText?> = coroutineScope {
        val downloadable =
            item as? DownloadableItem
                ?: return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(CoreR.string.unknown_error),
                )
        try {
            val source = downloadable.resolveDownloadSource(sourceId, jellyfinRepository)
            val segments =
                if (downloadable.hasVideoExtras) jellyfinRepository.getSegments(item.id)
                else emptyList()
            val trickplayInfo =
                if (downloadable.hasVideoExtras && item is FindroidSources) {
                    item.trickplayInfo?.get(sourceId)
                } else {
                    null
                }
            val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
            if (
                storageLocation == null ||
                    Environment.getExternalStorageState(storageLocation) !=
                        Environment.MEDIA_MOUNTED
            ) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(CoreR.string.storage_unavailable),
                )
            }
            val path =
                Uri.fromFile(File(storageLocation, "downloads/${item.id}.${source.id}.download"))
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(
                        CoreR.string.not_enough_storage,
                        Formatter.formatFileSize(context, source.size),
                        Formatter.formatFileSize(context, stats.availableBytes),
                    ),
                )
            }
            val request =
                DownloadManager.Request(source.path.toUri())
                    .setTitle(item.name)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationUri(path)
            val downloadId = downloadManager.enqueue(request)

            downloadable.persistDownloadMetadata(
                database,
                jellyfinRepository,
                appPreferences.getValue(appPreferences.currentServer),
            )
            // An episode is persisted together with its show and season; fetch their images too
            // so the offline show/season screens are not blank. Books and movies have no parents.
            if (item is FindroidEpisode) {
                startImagesDownloaderById(item.seriesId)
                startImagesDownloaderById(item.seasonId)
            }

            val sourceDto = source.toFindroidSourceDto(item.id, path.path.orEmpty())

            database.insertSource(sourceDto.copy(downloadId = downloadId))
            database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))

            if (downloadable.hasVideoExtras) {
                downloadExternalMediaStreams(item, source, storageIndex)
                segments.forEach { database.insertSegment(it.toFindroidSegmentsDto(item.id)) }
            }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, sourceId, trickplayInfo)
            }

            startImagesDownloader(item)
            return@coroutineScope Pair(downloadId, null)
        } catch (e: Exception) {
            try {
                deleteItem(item, downloadable.resolveDownloadSource(sourceId, jellyfinRepository))
            } catch (_: Exception) {}
            Timber.e(e)
            return@coroutineScope Pair(
                -1,
                if (e.message != null) UiText.DynamicString(e.message!!)
                else UiText.StringResource(CoreR.string.unknown_error),
            )
        }
    }

    override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) {
        val source =
            database.getSourceByDownloadId(downloadId)?.toFindroidSource(database) ?: return
        if (source.downloadId != null) {
            downloadManager.remove(source.downloadId!!)
        }
        deleteItem(item, source)
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        (item as? DownloadableItem)?.deleteDownloadMetadata(database, context.filesDir)

        database.deleteSource(source.id)
        File(source.path).delete()

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    override suspend fun getProgress(downloadId: Long?): Pair<Int, Int> {
        var downloadStatus = -1
        var progress = -1
        if (downloadId == null) {
            return Pair(downloadStatus, progress)
        }
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                downloadStatus =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (downloadStatus) {
                    DownloadManager.STATUS_RUNNING -> {
                        val totalBytes =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                )
                            )
                        if (totalBytes > 0) {
                            val downloadedBytes =
                                cursor.getLong(
                                    cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                    )
                                )
                            progress = downloadedBytes.times(100).div(totalBytes).toInt()
                        }
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        progress = 100
                    }
                }
            } else {
                downloadStatus = DownloadManager.STATUS_FAILED
            }
        }
        return Pair(downloadStatus, progress)
    }

    private suspend fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageIndex: Int = 0,
    ) {
        val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val id = UUID.randomUUID()
            val streamPath =
                Uri.fromFile(
                    File(storageLocation, "downloads/${item.id}.${source.id}.$id.download")
                )
            database.insertMediaStream(
                mediaStream.toFindroidMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )
            val request =
                DownloadManager.Request(mediaStream.path!!.toUri())
                    .setTitle(mediaStream.title)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    .setDestinationUri(streamPath)
            val downloadId = downloadManager.enqueue(request)
            database.setMediaStreamDownloadId(id, downloadId)
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private suspend fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    private fun startImagesDownloader(item: FindroidItem) {
        startImagesDownloaderById(item.id)
    }

    private fun startImagesDownloaderById(itemId: UUID) {
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to itemId.toString()))
                .build()

        workManager.enqueue(downloadImagesRequest)
    }
}
