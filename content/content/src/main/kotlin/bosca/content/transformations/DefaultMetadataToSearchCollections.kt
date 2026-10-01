package bosca.content.transformations

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.search.model.SearchDocumentItem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DefaultMetadataToSearchCollections(
    private val collectionsService: CollectionService
) : MetadataToSearchCollections {

    override suspend fun toCollections(metadataService: MetadataService, metadata: Metadata): Map<String, List<SearchDocumentItem>> {
        val parents = metadataService.getParents(metadata.id).groupByTo(mutableMapOf()) {
            val type = it.attributes?.takeIf { it !is JsonNull }?.jsonObject?.get("type")?.jsonPrimitive?.content?.lowercase() ?: "collection"
            if (type.endsWith("s")) type else "${type}s"
        }
        val characters = parents.remove("character") ?: emptyList()
        parents["characters"] = characters.toMutableList()
        return parents.filter { it.key != "episode" && it.key != "season" }.mapValues {
            it.value.map {
                val name = collectionsService.getLanguageVariants(it.id).firstOrNull { it.languageTag.equals(metadata.languageTag, ignoreCase = true) && (it.isPublished || it.isAdvertised) }?.name ?: it.name
                SearchDocumentItem(it.id.toString(), name)
            }
        }
    }
}
