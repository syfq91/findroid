package dev.jdtech.jellyfin.presentation.book

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.BookReaderActivity
import dev.jdtech.jellyfin.book.presentation.book.BookAction
import dev.jdtech.jellyfin.book.presentation.book.BookState
import dev.jdtech.jellyfin.book.presentation.book.BookViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.book.components.BookButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import java.util.UUID

@Composable
fun BookScreen(
    bookId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    viewModel: BookViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId = bookId)
    }

    BookScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is BookAction.OnReadClick -> {
                    val intent = Intent(context, BookReaderActivity::class.java).apply {
                        putExtra(BookReaderActivity.EXTRA_BOOK_ID, bookId.toString())
                        putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, state.book?.name.orEmpty())
                    }
                    context.startActivity(intent)
                }
                is BookAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onHomeClick = navigateHome,
    )
}

@Composable
private fun BookScreenLayout(
    state: BookState,
    onAction: (BookAction) -> Unit,
    onHomeClick: () -> Unit,
) {
    val safePadding = rememberSafePadding()
    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        state.book?.let { book ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = book,
                    scrollState = scrollState,
                    content = {
                        Column(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd)
                        ) {
                            Text(
                                text = book.name,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            book.originalTitle?.let { originalTitle ->
                                if (originalTitle != book.name) {
                                    Text(
                                        text = originalTitle,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    },
                )

                Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        book.productionYear?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        book.container?.let { container ->
                            Text(
                                text = "•  ${container.uppercase()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    if (book.authors.isNotEmpty()) {
                        Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                        Text(
                            text = book.authors.joinToString(", "),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    book.seriesName?.let { series ->
                        Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                        Text(
                            text = if (book.seriesIndex != null) "$series #${book.seriesIndex}" else series,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(MaterialTheme.spacings.default))

                    BookButtonsBar(
                        book = book,
                        onReadClick = { onAction(BookAction.OnReadClick) },
                        onMarkAsPlayedClick = {
                            if (book.played) {
                                onAction(BookAction.UnmarkAsPlayed)
                            } else {
                                onAction(BookAction.MarkAsPlayed)
                            }
                        },
                        onMarkAsFavoriteClick = {
                            if (book.favorite) {
                                onAction(BookAction.UnmarkAsFavorite)
                            } else {
                                onAction(BookAction.MarkAsFavorite)
                            }
                        },
                    )

                    Spacer(Modifier.height(MaterialTheme.spacings.default))

                    if (book.overview.isNotBlank()) {
                        OverviewText(overview = book.overview)
                        Spacer(Modifier.height(MaterialTheme.spacings.default))
                    }

                    if (book.genres.isNotEmpty()) {
                        Text(
                            text = stringResource(CoreR.string.genres),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                        Text(
                            text = book.genres.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.default))
                    }

                    Spacer(Modifier.height(paddingBottom))
                }
            }

            ItemTopBar(
                onBackClick = { onAction(BookAction.OnBackClick) },
                onHomeClick = onHomeClick,
                scrollState = scrollState,
            )
        }

        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        state.error?.let { error ->
            Text(
                text = error.localizedMessage ?: stringResource(CoreR.string.unknown_error),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
