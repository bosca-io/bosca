package bosca.design.example

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Local save and refresh outcomes for the independent workspace consumer. */
@Serializable
class WorkspaceState {
    var updates: Int = 0
        private set
    var reject: Boolean = false
        private set
    var saves: Int = 0
        private set
    var error: String = ""
        private set
    val source: String get() = (1..120).joinToString("\n") { "export const row$it = \"Workspace source $it\";" }

    fun update() { updates++ }
    fun prepareFailure() { reject = true }

    suspend fun save(body: String) {
        require(body.isNotBlank()) { "Enter a draft." }
        delay(60)
        saves++
        error = if (reject) "Draft rejected; keep editing." else ""
        reject = false
    }
}
