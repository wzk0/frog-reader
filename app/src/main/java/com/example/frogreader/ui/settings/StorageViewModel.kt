package com.example.frogreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.BookStorage
import com.example.frogreader.data.model.CacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the storage screen: what the books cost, what the caches cost, and the
 * two actions that remove them.
 *
 * Sizes are measured on every visit rather than remembered. The point of the
 * screen is that the numbers are real — extracting images or opening a book
 * changes them, and a stale figure would make "clear caches" look ineffective.
 */
class StorageViewModel(
    private val repository: BookRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val books: List<BookStorage> = emptyList(),
        val caches: CacheStorage = CacheStorage(),
        val working: Boolean = false,
    ) {
        val bookBytes: Long get() = books.sumOf { it.bytes }

        val totalBytes: Long get() = bookBytes + caches.totalBytes
    }

    /** A one-off line for the snackbar, consumed by the page. */
    sealed interface Message {
        data class Deleted(val title: String) : Message
        data class Cleared(val bytes: Long) : Message
        data class Failed(val reason: String) : Message
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    init {
        // A StateFlow emits its current value immediately, so this is also the
        // first load; later library changes (import, restore, delete) arrive here.
        viewModelScope.launch { repository.books.collect { reload() } }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val books = repository.bookStorage()
            val caches = repository.readerCacheSizes()
            _state.value = _state.value.copy(loading = false, books = books, caches = caches)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(loading = false)
            _message.value = Message.Failed(error.message.orEmpty())
        }
    }

    /**
     * Removes the book, its source file and the derived caches with it.
     *
     * Suspending so the caller can let the row finish collapsing first: the page
     * owns that animation, and a row deleted out from under it would vanish
     * instead of leaving.
     */
    suspend fun deleteBook(book: BookStorage) {
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true)
        try {
            repository.deleteBook(book.id)
            _state.value = _state.value.copy(
                working = false,
                books = _state.value.books.filterNot { it.id == book.id },
                caches = repository.readerCacheSizes(),
            )
            _message.value = Message.Deleted(book.title)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(working = false)
            _message.value = Message.Failed(error.message.orEmpty())
        }
    }

    suspend fun clearCaches() {
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true)
        try {
            val freed = repository.clearReaderCaches()
            _state.value = _state.value.copy(
                working = false,
                caches = repository.readerCacheSizes(),
            )
            _message.value = Message.Cleared(freed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(working = false)
            _message.value = Message.Failed(error.message.orEmpty())
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                StorageViewModel(app.bookRepository)
            }
        }
    }
}