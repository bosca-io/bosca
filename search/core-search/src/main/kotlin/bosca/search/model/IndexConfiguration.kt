package bosca.search.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IndexConfiguration(
    @SerialName("indexName")
    val name: String,
    val primaryKey: String = "id",
    val contentIndex: Boolean = true,
    val filterable: List<String> = emptyList(),
    val sortable: List<String> = emptyList(),
    val searchable: List<String> = emptyList(),
    val embedders: List<IndexEmbedder> = emptyList(),
    val chat: IndexChatConfiguration? = null,
    val chatSettings: List<IndexChatSettingsConfiguration> = emptyList(),
)

@Serializable
data class IndexEmbedder(
    val name: String,
    val source: String,
    val model: String,
    val dimensions: Int? = null,
    val apiKey: String? = null,
    val documentTemplate: String? = null,
    val documentTemplateMaxBytes: Int = 400
)

@Serializable
data class IndexChatConfiguration(
    val description: String,
    val documentTemplate: String,
    val documentTemplateMaxBytes: Int,
    val searchParameters: IndexChatSearchParameters? = null
)

@Serializable
data class IndexChatSearchParameters(
    val hybrid: IndexChatHybrid,
    val limit: Int? = null,
)

@Serializable
data class IndexChatHybrid(
    val semanticRatio: Double? = null,
    val embedder: String
)

@Serializable
data class IndexChatSettingsConfiguration(
    val name: String,
    val source: String,
    val apiKey: String? = null,
    val prompts: IndexChatSearchPrompt? = null
)

@Serializable
data class IndexChatSearchPrompt(
    val system: String,
    val searchDescription: String? = null,
    val searchQParam: String? = null,
    val searchIndexUidParam: String? = null,
)
