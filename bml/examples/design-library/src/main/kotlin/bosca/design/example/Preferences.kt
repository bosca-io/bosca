package bosca.design.example

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Application-owned state used to exercise library controls through ordinary BML actions. */
@Serializable
class Preferences(val id: String) {
    var savedName: String = "Ada"
        private set
    var saves: Int = 0
        private set
    var clicks: Int = 0
        private set

    suspend fun save(displayName: String) {
        require(displayName.isNotBlank()) { "A display name is required" }
        delay(250)
        savedName = displayName
        saves++
    }

    fun increment() {
        clicks++
    }
}
