package bosca.analytics.instrumentation

import bosca.analytics.api.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Tracks the currently composed screen for events created outside a Compose callback. */
class AnalyticsPageState : bosca.analytics.api.CurrentPageProvider {
    private val pages = MutableStateFlow<List<Entry>>(emptyList())

    val current: Page?
        get() = pages.value.lastOrNull()?.page

    override fun currentPage(): Page? = current

    /** Activates [page] for [key] and returns whether the visible page path changed. */
    fun enter(key: Any, page: Page): Boolean {
        val previousPath = current?.path
        pages.update { current -> current.filterNot { it.key == key } + Entry(key, page) }
        return previousPath != page.path
    }

    fun leave(key: Any) {
        pages.update { current -> current.filterNot { it.key == key } }
    }

    fun clear() {
        pages.value = emptyList()
    }

    private data class Entry(val key: Any, val page: Page)
}
