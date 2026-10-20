package dev.jdtech.jellyfin.book.presentation.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidBook
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class BookViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BookState())
    val state = _state.asStateFlow()

    lateinit var bookId: UUID

    fun loadBook(bookId: UUID) {
        this.bookId = bookId
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val book = repository.getBook(bookId)
                _state.emit(
                    _state.value.copy(
                        book = book,
                        isLoading = false,
                        error = null,
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load book $bookId")
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    fun onAction(action: BookAction) {
        when (action) {
            is BookAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(bookId)
                    loadBook(bookId)
                }
            }
            else -> Unit
        }
    }
}
