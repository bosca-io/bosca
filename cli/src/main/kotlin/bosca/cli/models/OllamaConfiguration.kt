package bosca.cli.models

import kotlinx.serialization.Serializable

@Serializable
data class OllamaConfiguration(
    val apiKey: String,
    val models: Map<String, String>,
    val url: String
) {

    companion object {
        const val KEY = "openai"
    }
}