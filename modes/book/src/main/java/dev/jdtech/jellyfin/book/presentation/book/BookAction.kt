package dev.jdtech.jellyfin.book.presentation.book

sealed interface BookAction {
    data object MarkAsPlayed : BookAction
    data object UnmarkAsPlayed : BookAction
    data object MarkAsFavorite : BookAction
    data object UnmarkAsFavorite : BookAction
    data object OnBackClick : BookAction
    data object OnReadClick : BookAction
}
