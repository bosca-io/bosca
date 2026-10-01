package bosca.content.transformations

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DefaultMetadataToSearchAttributes(
    private val documentService: DocumentService,
    private val guideService: GuideService,
    private val dataService: DataService
) : MetadataToSearchAttributes {

    override suspend fun toAttributes(metadataService: MetadataService, metadata: Metadata): JsonObject {
        val attributes = (metadata.attributes?.takeIf { it !is JsonNull && it is JsonObject }?.jsonObject ?: emptyMap()).toMutableMap()
        if (attributes.containsKey("episode")) {
            attributes["episode"] = JsonPrimitive(attributes["episode"]!!.jsonPrimitive.content.replace("\"", "").trim())
        }
        if (attributes.containsKey("season")) {
            attributes["season"] = JsonPrimitive(attributes["season"]!!.jsonPrimitive.content.replace("\"", "").trim())
        }
        val document = documentService.getDocument(metadata.id, metadata.version)
        val guide = guideService.getGuide(metadata.id, metadata.version)
        val data = dataService.getData(metadata.id, metadata.version)
        attributes["hasDocument"] = JsonPrimitive(document != null)
        attributes["hasGuide"] = JsonPrimitive(guide != null)
        attributes["hasData"] = JsonPrimitive(data != null)
        return JsonObject(attributes)
    }
}
