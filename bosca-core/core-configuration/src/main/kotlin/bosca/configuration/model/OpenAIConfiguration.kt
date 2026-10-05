package bosca.configuration.model

import kotlinx.serialization.Serializable

@Serializable
data class OpenAIConfiguration(val key: String) {

    companion object {

        const val KEY = "openai"
    }
}