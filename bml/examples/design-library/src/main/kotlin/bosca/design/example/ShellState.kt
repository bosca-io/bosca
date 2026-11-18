package bosca.design.example

import kotlinx.serialization.Serializable

@Serializable
data class ShellState(
    var light: Boolean = false,
    var name: String = "Alex Example",
    var notice: String = "",
    var sequence: Int = 0,
) {
    fun appearance() { light = !light }
    fun select() { notice = "Profile selected."; sequence += 1 }
    fun rename(value: String) {
        name = value.trim().ifBlank { "Alex Example" }
        notice = "Display name saved."
        sequence += 1
    }
}
