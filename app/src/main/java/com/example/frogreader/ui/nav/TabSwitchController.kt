package com.example.frogreader.ui.nav

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One destination at a time: repeated taps do not restart an active transition. */
internal class TabSwitchController(
    private val scope: CoroutineScope,
    private val switchPage: suspend (Int) -> Unit,
) {
    private var transition: Job? = null
    private var requestId = 0
    private val _requestedPage = MutableStateFlow<Int?>(null)
    val requestedPage = _requestedPage.asStateFlow()

    fun select(page: Int) {
        if (transition?.isActive == true && _requestedPage.value == page) return
        val id = ++requestId
        transition?.cancel()
        _requestedPage.value = page
        transition = scope.launch {
            try {
                switchPage(page)
            } finally {
                if (requestId == id) _requestedPage.value = null
            }
        }
    }
}
