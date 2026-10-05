package bosca.cli.models

import kotlinx.serialization.Serializable

@Serializable
data class GoogleConfiguration(
    val apiKey: String? = null,
    val vertexAI: Boolean = false
) {

    companion object {
        const val KEY = "google.ai"
    }
}