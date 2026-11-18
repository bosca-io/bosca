package bosca.bml.gradle

import java.io.BufferedReader

/** Reads application messages while ignoring named SSE events such as heartbeat pings. */
internal fun readReloadToken(reader: BufferedReader, previous: String? = null): String {
    var event = "message"
    val data = mutableListOf<String>()
    while (true) {
        val line = requireNotNull(reader.readLine()) { "Reload stream closed unexpectedly" }
        when {
            line.isEmpty() -> {
                val value = data.joinToString("\n")
                if (event == "message" && value.isNotEmpty() && value != previous) return value
                event = "message"
                data.clear()
            }
            line.startsWith("event:") -> event = line.removePrefix("event:").removePrefix(" ").ifEmpty { "message" }
            line.startsWith("data:") -> data.add(line.removePrefix("data:").removePrefix(" "))
        }
    }
}
