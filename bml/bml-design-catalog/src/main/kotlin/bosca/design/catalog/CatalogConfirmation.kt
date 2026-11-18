package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Local failed-action example; no product record is removed. */
@Serializable
data class CatalogConfirmation(val id: String, var reason: String = "", var error: String = "", var attempts: Int = 0) {
    suspend fun remove(value: String) {
        delay(150)
        reason = value
        error = "This sample is still in use. Its removal was rejected."
        attempts++
    }
}
