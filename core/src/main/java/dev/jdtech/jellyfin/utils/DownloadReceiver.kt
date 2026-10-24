package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.toFindroidBook
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import timber.log.Timber

@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var database: ServerDatabaseDao

    @Inject lateinit var downloader: Downloader

    @Inject lateinit var repository: JellyfinRepository

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onReceive(context: Context, intent: Intent) =
        launchAsync(ioScope) {
            if (intent.action == "android.intent.action.DOWNLOAD_COMPLETE") {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != -1L) {
                    val downloadManager = context.getSystemService(DownloadManager::class.java)
                    var downloadStatus = -1
                    var failureReason = -1
                    try {
                        downloadManager?.query(DownloadManager.Query().setFilterById(id))?.use {
                            cursor ->
                            if (cursor.moveToFirst()) {
                                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                if (statusIdx != -1) downloadStatus = cursor.getInt(statusIdx)
                                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                if (reasonIdx != -1) failureReason = cursor.getInt(reasonIdx)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to query download status for downloadId $id")
                    }

                    val source = database.getSourceByDownloadId(id)
                    if (source != null) {
                        val path = source.path.replace(".download", "")
                        val sourceFile = File(source.path)
                        val targetFile = File(path)
                        val isSuccess =
                            downloadStatus == DownloadManager.STATUS_SUCCESSFUL ||
                                (downloadStatus == -1 &&
                                    sourceFile.exists() &&
                                    sourceFile.length() > 0)
                        val successfulMove = isSuccess && safeMove(sourceFile, targetFile)

                        if (successfulMove) {
                            Timber.d(
                                "Download completed successfully for source ${source.id}, saved to $path"
                            )
                            database.setSourcePath(source.id, path)
                        } else {
                            Timber.w(
                                "Download failed or move failed for source ${source.id} (itemId=${source.itemId}, status=$downloadStatus, reason=$failureReason)"
                            )
                            val items = mutableListOf<FindroidItem>()
                            items.addAll(
                                database.getMovies().map {
                                    it.toFindroidMovie(database, repository.getUserId())
                                }
                            )
                            items.addAll(
                                database.getEpisodes().map {
                                    it.toFindroidEpisode(database, repository.getUserId())
                                }
                            )
                            items.addAll(
                                database.getBooks().map {
                                    it.toFindroidBook(database, repository.getUserId())
                                }
                            )

                            items
                                .firstOrNull { it.id == source.itemId }
                                ?.let {
                                    downloader.deleteItem(it, source.toFindroidSource(database))
                                }
                        }
                    } else {
                        val mediaStream = database.getMediaStreamByDownloadId(id)
                        if (mediaStream != null) {
                            val path = mediaStream.path.replace(".download", "")
                            val sourceFile = File(mediaStream.path)
                            val targetFile = File(path)
                            val isSuccess =
                                downloadStatus == DownloadManager.STATUS_SUCCESSFUL ||
                                    (downloadStatus == -1 &&
                                        sourceFile.exists() &&
                                        sourceFile.length() > 0)
                            val successfulMove = isSuccess && safeMove(sourceFile, targetFile)
                            if (successfulMove) {
                                Timber.d(
                                    "Download completed successfully for media stream ${mediaStream.id}, saved to $path"
                                )
                                database.setMediaStreamPath(mediaStream.id, path)
                            } else {
                                Timber.w(
                                    "Download failed or move failed for media stream ${mediaStream.id} (status=$downloadStatus, reason=$failureReason)"
                                )
                                sourceFile.delete()
                                targetFile.delete()
                                database.deleteMediaStream(mediaStream.id)
                            }
                        }
                    }
                }
            }
        }

    private fun safeMove(sourceFile: File, targetFile: File): Boolean {
        if (!sourceFile.exists()) {
            Timber.w("Downloaded file does not exist: ${sourceFile.absolutePath}")
            return false
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (sourceFile.renameTo(targetFile)) {
            return true
        }
        return try {
            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()
            true
        } catch (e: Exception) {
            Timber.e(
                e,
                "Failed to copy/move ${sourceFile.absolutePath} to ${targetFile.absolutePath}",
            )
            false
        }
    }
}
