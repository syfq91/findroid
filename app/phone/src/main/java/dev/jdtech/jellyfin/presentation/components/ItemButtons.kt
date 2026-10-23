package dev.jdtech.jellyfin.presentation.components

import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR

/** Toggles the played state. Filled red when the item is already marked as played. */
@Composable
fun MarkPlayedButton(played: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_check),
            contentDescription = stringResource(CoreR.string.check_button_description),
            tint = if (played) Color.Red else LocalContentColor.current,
        )
    }
}

/** Toggles the favourite state. Filled red when the item is already a favourite. */
@Composable
fun FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter =
                if (favorite) {
                    painterResource(CoreR.drawable.ic_heart_filled)
                } else {
                    painterResource(CoreR.drawable.ic_heart)
                },
            contentDescription = stringResource(CoreR.string.favorite_button_description),
            tint = if (favorite) Color.Red else LocalContentColor.current,
        )
    }
}
