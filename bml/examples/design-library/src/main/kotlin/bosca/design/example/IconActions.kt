package bosca.design.example

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Caller-owned state for icon actions, labels and repeated component mounting. */
@Serializable
class IconActions(val id: String) {
    var count: Int = 0
        private set
    var label: String = "Increment"
        private set
    var visible: Boolean = true
        private set
    var savedName: String = ""
        private set
    var saves: Int = 0
        private set

    fun increment() {
        count++
    }

    fun rename() {
        label = "Updated action"
    }

    fun toggle() {
        visible = !visible
    }

    suspend fun save(name: String) {
        require(name.isNotBlank()) { "A name is required" }
        delay(250)
        savedName = name
        saves++
    }
}
