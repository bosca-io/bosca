package bosca.content.transformations

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.set

class DefaultCollectionToSearchAttributes : CollectionToSearchAttributes {

    override suspend fun toAttributes(collectionService: CollectionService, collection: Collection, variant: CollectionLanguageVariant?): JsonObject {
        val baseData = collection.attributes?.takeIf { it !is JsonNull }?.jsonObject ?: emptyMap()
        val variantData = variant?.attributes?.takeIf { it !is JsonNull }?.jsonObject ?: emptyMap()
        val data = (baseData + variantData).toMutableMap()
        if (data.containsKey("episode")) {
            data["episode"] = JsonPrimitive(data["episode"]!!.jsonPrimitive.toString())
        }
        if (data.containsKey("season")) {
            data["season"] = JsonPrimitive(data["season"]!!.jsonPrimitive.toString().replace("\"", ""))
        }
        return JsonObject(data)
    }
}
