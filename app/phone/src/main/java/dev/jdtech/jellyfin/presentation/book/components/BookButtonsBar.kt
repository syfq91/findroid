package dev.jdtech.jellyfin.presentation.book.components

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.models.FindroidBook
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.film.components.CancelDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.DeleteDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.DownloaderCard
import dev.jdtech.jellyfin.presentation.film.components.StorageSelectionDialog
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun BookButtonsBar(
    book: FindroidBook,
    onReadClick: () -> Unit,
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    onDownloadClick: (storageIndex: Int) -> Unit,
    onDownloadCancelClick: () -> Unit,
    onDownloadDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloaderState: DownloaderState? = null,
) {
    val context = LocalContext.current

    var storageSelectionDialogOpen by remember { mutableStateOf(false) }
    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }

    var selectedStorageIndex by remember { mutableIntStateOf(0) }
    var storageLocations = remember { context.getExternalFilesDirs(null) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onReadClick,
                    modifier = Modifier.weight(1f),
                    enabled = book.canPlay,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_book_open),
                        contentDescription = stringResource(CoreR.string.read_button_description),
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                    Text(
                        text =
                            if (book.playbackPositionTicks > 0 && !book.played) {
                                stringResource(CoreR.string.resume_reading)
                            } else {
                                stringResource(CoreR.string.read)
                            }
                    )
                }

                FilledTonalIconButton(onClick = onMarkAsPlayedClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_check),
                        contentDescription = stringResource(CoreR.string.check_button_description),
                        tint = if (book.played) Color.Red else LocalContentColor.current,
                    )
                }

                FilledTonalIconButton(onClick = onMarkAsFavoriteClick) {
                    Icon(
                        painter =
                            if (book.favorite) {
                                painterResource(CoreR.drawable.ic_heart_filled)
                            } else {
                                painterResource(CoreR.drawable.ic_heart)
                            },
                        contentDescription = stringResource(CoreR.string.favorite_button_description),
                        tint = if (book.favorite) Color.Red else LocalContentColor.current,
                    )
                }

                if (downloaderState != null && !downloaderState.isDownloading) {
                    if (book.isDownloaded()) {
                        FilledTonalIconButton(onClick = { deleteDownloadDialogOpen = true }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_trash),
                                contentDescription = null,
                            )
                        }
                    } else if (book.canDownload) {
                        FilledTonalIconButton(
                            onClick = {
                                storageLocations = context.getExternalFilesDirs(null)
                                if (storageLocations.size > 1) {
                                    storageSelectionDialogOpen = true
                                } else {
                                    selectedStorageIndex = 0
                                    onDownloadClick(selectedStorageIndex)
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_download),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            if (downloaderState != null) {
                AnimatedVisibility(downloaderState.isDownloading) {
                    Column {
                        DownloaderCard(
                            state = downloaderState,
                            onCancelClick = { cancelDownloadDialogOpen = true },
                            onRetryClick = { onDownloadClick(selectedStorageIndex) },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                }
            }
        }

        if (storageSelectionDialogOpen) {
            val locations = remember {
                storageLocations.map { dir ->
                    val locationStringRes =
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    val locationString = context.getString(locationStringRes)

                    val stat = StatFs(dir.path)
                    val availableMegaBytes = stat.availableBytes.div(1000000)
                    context.getString(CoreR.string.storage_name, locationString, availableMegaBytes)
                }
            }
            StorageSelectionDialog(
                storageLocations = locations,
                onSelect = { storageIndex ->
                    selectedStorageIndex = storageIndex
                    onDownloadClick(selectedStorageIndex)
                    storageSelectionDialogOpen = false
                },
                onDismiss = { storageSelectionDialogOpen = false },
            )
        }
        if (cancelDownloadDialogOpen) {
            CancelDownloadDialog(
                onCancel = {
                    onDownloadCancelClick()
                    cancelDownloadDialogOpen = false
                },
                onDismiss = { cancelDownloadDialogOpen = false },
            )
        }
        if (deleteDownloadDialogOpen) {
            DeleteDownloadDialog(
                onDelete = {
                    onDownloadDeleteClick()
                    deleteDownloadDialogOpen = false
                },
                onDismiss = { deleteDownloadDialogOpen = false },
            )
        }
    }
}
