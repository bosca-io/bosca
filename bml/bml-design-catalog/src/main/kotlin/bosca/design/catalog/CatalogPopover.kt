package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** A local popover form, independent of its surrounding example's modal state. */
@Serializable
class CatalogPopover(val id: String) {
    var filter: String = "Components"
        private set
    var sequence: Int = 0
        private set

    suspend fun apply(value: String) {
        delay(150)
        filter = value
        sequence++
    }
}
