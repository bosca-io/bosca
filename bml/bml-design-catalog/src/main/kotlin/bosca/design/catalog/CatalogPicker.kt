package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Form validation and interactive state belong to each catalog example. */
@Serializable
class CatalogPicker(val id: String) {
    var value: String = ""
        private set
    var error: String = ""
        private set
    var message: String = ""
        private set
    var saves: Int = 0
        private set
    var failSearch: Boolean = false
        private set
    var disabled: Boolean = false
        private set
    var loading: Boolean = false
        private set

    suspend fun save(choice: String) {
        delay(150)
        val option = CatalogChoiceSearch.options.find { it.value == choice && !it.disabled }
        error = if (option == null) "Choose an available example." else ""
        message = ""
        if (option == null) return
        value = option.value
        saves++
        message = "Saved ${option.label.substringBefore(" · ")}."
    }

    fun toggleFailure() { failSearch = !failSearch }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleLoading() { loading = !loading }
}
