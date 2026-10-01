package bosca.search.model

import bosca.category.model.Category
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.search.IndexStorageSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SearchTransformConfiguration(
    val expressions: SearchTransformExpressions = SearchTransformExpressions()
)

@Serializable
data class SearchTransformExpressions(
    val metadata: String? = null,
    val collection: String? = null,
    val profile: String? = null
)

@Serializable
data class MetadataSearchContext(
    val metadata: Metadata,
    val content: String,
    val relationships: Map<String, List<MetadataRelationship>>,
    val categories: List<Category>,
    val collections: Map<String, List<SearchDocumentItem>>,
    val attributes: JsonObject,
    val slug: String,
    val bibleBooks: JsonElement?,
    val bibleUsfms: List<String> = emptyList()
)

@Serializable
data class CollectionVariantSearchContext(
    val variant: CollectionLanguageVariant?,
    val collections: Map<String, List<SearchDocumentItem>>,
    val attributes: JsonObject,
    val slug: String?
)

@Serializable
data class CollectionSearchContext(
    val collection: Collection,
    val categories: List<Category>,
    val variants: List<CollectionVariantSearchContext>,
    val slug: String,
    val isAdmin: Boolean,
)

@Serializable
data class ProfileSearchContext(
    val storage: IndexStorageSystem,
    val profile: Profile,
    val contentType: String,
    val organization: Organization?,
    val organizations: List<Organization>,
    val memberCount: Long = 0,
    val attributes: List<ProfileAttribute>,
    val slug: String
)
