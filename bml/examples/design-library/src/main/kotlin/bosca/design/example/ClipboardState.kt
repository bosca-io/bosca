package bosca.design.example

import kotlinx.serialization.Serializable

/** Consumer-owned values and labels for clipboard component updates and disposal. */
@Serializable
class ClipboardState(val id: String) {
    var value: String = "Token \"alpha\" & beta"
        private set
    var label: String = "Copy token"
        private set
    var mounted: Boolean = true
        private set

    fun change() {
        value = "Updated \"value\" & text / ?=1"
        label = "Copy updated value"
    }

    fun toggle() {
        mounted = !mounted
    }
}
