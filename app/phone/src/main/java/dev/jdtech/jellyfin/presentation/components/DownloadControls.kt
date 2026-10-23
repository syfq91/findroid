package dev.jdtech.jellyfin.presentation.components

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.film.components.CancelDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.DeleteDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.DownloaderCard
import dev.jdtech.jellyfin.presentation.film.components.StorageSelectionDialog
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.io.File

/**
 * State shared by [DownloadButton], [DownloadProgress] and [DownloadDialogs].
 *
 * The download controls are split across three call sites because they render at different
 * positions: the button sits inside a button row, the progress card sits below that row, and the
 * dialogs render as separate windows. They must agree on the chosen storage index and which dialog
 * is open, so that state lives here and is remembered once per bar.
 */
@Stable
class DownloadControlsState {
    var storageSelectionDialogOpen by mutableStateOf(false)
    var cancelDownloadDialogOpen by mutableStateOf(false)
    var deleteDownloadDialogOpen by mutableStateOf(false)
    var selectedStorageIndex by mutableIntStateOf(0)
    var storageLocations: Array<File> = emptyArray()
}

/** Creates the [DownloadControlsState] shared by the download button, progress card and dialogs. */
@Composable
fun rememberDownloadControlsState(): DownloadControlsState {
    return remember { DownloadControlsState() }
}

/**
 * The download / delete action button.
 *
 * Shows a delete (trash) button when the item is already downloaded, and a download button when it
 * can still be downloaded. Hidden entirely while a download is in progress. Starting a download
 * either opens the storage-selection dialog (when more than one storage is available) or downloads
 * directly to the default storage.
 */
@Composable
fun DownloadButton(
    item: FindroidItem,
    state: DownloadControlsState,
    downloaderState: DownloaderState?,
    onDownloadClick: (storageIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloaderState != null && !downloaderState.isDownloading) {
        val context = LocalContext.current
        if (item.isDownloaded()) {
            FilledTonalIconButton(
                onClick = { state.deleteDownloadDialogOpen = true },
                modifier = modifier,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                )
            }
        } else if (item.canDownload) {
            FilledTonalIconButton(
                onClick = {
                    state.storageLocations = context.getExternalFilesDirs(null)
                    if (state.storageLocations.size > 1) {
                        state.storageSelectionDialogOpen = true
                    } else {
                        state.selectedStorageIndex = 0
                        onDownloadClick(0)
                    }
                },
                modifier = modifier,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_download),
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * The download progress card, shown below the action row while a download is running. Lets the user
 * cancel an in-flight download or retry a failed one (reusing the storage chosen at start).
 */
@Composable
fun DownloadProgress(
    state: DownloadControlsState,
    downloaderState: DownloaderState?,
    onDownloadClick: (storageIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloaderState != null) {
        AnimatedVisibility(visible = downloaderState.isDownloading, modifier = modifier) {
            Column {
                DownloaderCard(
                    state = downloaderState,
                    onCancelClick = { state.cancelDownloadDialogOpen = true },
                    onRetryClick = { onDownloadClick(state.selectedStorageIndex) },
                )
                Spacer(Modifier.height(MaterialTheme.spacings.small))
            }
        }
    }
}

/**
 * The storage-selection, cancel-download and delete-download dialogs, driven by [state]. These
 * render as separate dialog windows, so their position in the composition tree does not affect
 * layout.
 */
@Composable
fun DownloadDialogs(
    state: DownloadControlsState,
    onDownloadClick: (storageIndex: Int) -> Unit,
    onDownloadCancelClick: () -> Unit,
    onDownloadDeleteClick: () -> Unit,
) {
    if (state.storageSelectionDialogOpen) {
        val resources = LocalResources.current
        val locations =
            remember(resources, state.storageLocations) {
                state.storageLocations.map { dir ->
                    val locationStringRes =
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    val locationString = resources.getString(locationStringRes)

                    val stat = StatFs(dir.path)
                    val availableMegaBytes = stat.availableBytes.div(1000000)
                    resources.getString(
                        CoreR.string.storage_name,
                        locationString,
                        availableMegaBytes,
                    )
                }
            }
        StorageSelectionDialog(
            storageLocations = locations,
            onSelect = { storageIndex ->
                state.selectedStorageIndex = storageIndex
                onDownloadClick(storageIndex)
                state.storageSelectionDialogOpen = false
            },
            onDismiss = { state.storageSelectionDialogOpen = false },
        )
    }
    if (state.cancelDownloadDialogOpen) {
        CancelDownloadDialog(
            onCancel = {
                onDownloadCancelClick()
                state.cancelDownloadDialogOpen = false
            },
            onDismiss = { state.cancelDownloadDialogOpen = false },
        )
    }
    if (state.deleteDownloadDialogOpen) {
        DeleteDownloadDialog(
            onDelete = {
                onDownloadDeleteClick()
                state.deleteDownloadDialogOpen = false
            },
            onDismiss = { state.deleteDownloadDialogOpen = false },
        )
    }
}
