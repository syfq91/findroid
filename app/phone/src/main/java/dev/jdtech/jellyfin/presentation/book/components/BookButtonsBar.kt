package dev.jdtech.jellyfin.presentation.book.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.models.FindroidBook
import dev.jdtech.jellyfin.presentation.components.DownloadButton
import dev.jdtech.jellyfin.presentation.components.DownloadDialogs
import dev.jdtech.jellyfin.presentation.components.DownloadProgress
import dev.jdtech.jellyfin.presentation.components.FavoriteButton
import dev.jdtech.jellyfin.presentation.components.MarkPlayedButton
import dev.jdtech.jellyfin.presentation.components.rememberDownloadControlsState
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
    val downloadControls = rememberDownloadControlsState()

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

                MarkPlayedButton(played = book.played, onClick = onMarkAsPlayedClick)
                FavoriteButton(favorite = book.favorite, onClick = onMarkAsFavoriteClick)
                DownloadButton(
                    item = book,
                    state = downloadControls,
                    downloaderState = downloaderState,
                    onDownloadClick = onDownloadClick,
                )
            }

            DownloadProgress(
                state = downloadControls,
                downloaderState = downloaderState,
                onDownloadClick = onDownloadClick,
            )
        }

        DownloadDialogs(
            state = downloadControls,
            onDownloadClick = onDownloadClick,
            onDownloadCancelClick = onDownloadCancelClick,
            onDownloadDeleteClick = onDownloadDeleteClick,
        )
    }
}
