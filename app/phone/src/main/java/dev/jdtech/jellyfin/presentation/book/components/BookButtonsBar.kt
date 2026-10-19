package dev.jdtech.jellyfin.presentation.book.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidBook
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun BookButtonsBar(
    book: FindroidBook,
    onReadClick: () -> Unit,
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
    }
}
