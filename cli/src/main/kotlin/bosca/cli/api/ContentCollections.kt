package bosca.cli.api

import bosca.graphql.client.GraphQLUpload
import bosca.graphql.client.Upload
import bosca.graphql.client.execute
import bosca.graphql.client.executeUpload
import bosca.graphql.gen.*
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A child of a collection — either a sub-collection or a metadata item — unified for listing. */
sealed class CollectionItem {
    data class Collection(val collection: GetCollectionListData.Content.Collection.Items.Collection) : CollectionItem()
    data class Metadata(val metadata: GetCollectionListData.Content.Collection.Items.Metadata) : CollectionItem()

    val isCollection: Boolean
        get() = this is Collection

    val id: Uuid
        get() = when (this) {
            is Collection -> collection.id
            is Metadata -> metadata.id
        }

    val name: String
        get() = when (this) {
            is Collection -> collection.name
            is Metadata -> metadata.name
        }
}

data class CollectionAndItems(
    val collection: GetCollectionListData.Content.Collection,
    val items: List<CollectionItem>
)

class ContentCollections(network: NetworkClient) : Api(network) {

    suspend fun list(id: Uuid? = null): CollectionAndItems? {
        val collection = network.boscaGraphql.execute(GetCollectionList, GetCollectionList.Variables(id))
            .content.collection ?: return null
        return CollectionAndItems(
            collection,
            collection.items.mapNotNull { item ->
                when (item) {
                    is GetCollectionListData.Content.Collection.Items.Collection -> CollectionItem.Collection(item)
                    is GetCollectionListData.Content.Collection.Items.Metadata -> CollectionItem.Metadata(item)
                    else -> null
                }
            },
        )
    }

    suspend fun getAll(offset: Int, limit: Int): List<GetAllCollectionData.Content.FindCollections> =
        network.boscaGraphql.execute(GetAllCollection, GetAllCollection.Variables(offset, limit)).content.findCollections

    suspend fun get(id: Uuid? = null): GetCollectionData.Content.Collection? =
        network.boscaGraphql.execute(GetCollection, GetCollection.Variables(id)).content.collection

    suspend fun getParents(id: Uuid): List<GetCollectionParentsData.Content.Collection.ParentCollections> =
        network.boscaGraphql.execute(GetCollectionParents, GetCollectionParents.Variables(id)).content.collection?.parentCollections ?: emptyList()

    suspend fun getPermissions(id: Uuid): List<GetCollectionPermissionsData.Content.Collection.Permissions> =
        network.boscaGraphql.execute(GetCollectionPermissions, GetCollectionPermissions.Variables(id)).content.collection?.permissions ?: emptyList()

    suspend fun getRelationships(id: Uuid): List<GetCollectionRelationshipsData.Content.Collection.MetadataRelationships> =
        network.boscaGraphql.execute(GetCollectionRelationships, GetCollectionRelationships.Variables(id)).content.collection?.metadataRelationships ?: emptyList()

    suspend fun add(collection: CollectionInput): Uuid? =
        network.boscaGraphql.execute(AddCollection, AddCollection.Variables(collection)).content.collection.add.id

    suspend fun addSupplementary(supplementary: CollectionSupplementaryInput): AddCollectionSupplementaryData.Content.Collection.AddSupplementary =
        network.boscaGraphql.execute(AddCollectionSupplementary, AddCollectionSupplementary.Variables(supplementary)).content.collection.addSupplementary

    suspend fun setSupplementaryContents(supplementaryId: String, file: Upload): Boolean =
        setSupplementaryContents(Uuid.parse(supplementaryId), file)

    suspend fun setSupplementaryContents(supplementaryId: Uuid, file: Upload): Boolean =
        network.boscaGraphql.executeUpload(
            SetCollectionSupplementaryContents,
            SetCollectionSupplementaryContents.Variables(supplementaryId, file.contentType, file),
            listOf(GraphQLUpload("variables.file", file)),
        ).content.collection.setSupplementaryContents

    suspend fun getSupplementary(collectionId: Uuid): List<GetCollectionSupplementariesData.Content.Collection.Supplementary> =
        network.boscaGraphql.execute(GetCollectionSupplementaries, GetCollectionSupplementaries.Variables(collectionId)).content.collection?.supplementary ?: emptyList()

    suspend fun edit(id: Uuid, collection: CollectionInput): Uuid? =
        network.boscaGraphql.execute(EditCollection, EditCollection.Variables(id, collection)).content.collection.edit.id

    suspend fun setAttributes(id: Uuid, attributes: JsonElement?) {
        network.boscaGraphql.execute(SetCollectionAttributes, SetCollectionAttributes.Variables(id, attributes ?: JsonObject(emptyMap())))
    }

    suspend fun setSystemAttributes(id: Uuid, attributes: JsonElement?) {
        network.boscaGraphql.execute(SetCollectionSystemAttributes, SetCollectionSystemAttributes.Variables(id, attributes ?: JsonObject(emptyMap())))
    }

    suspend fun addPermission(input: PermissionInput) {
        network.boscaGraphql.execute(AddCollectionPermission, AddCollectionPermission.Variables(input))
    }

    suspend fun removePermission(input: PermissionInput) {
        network.boscaGraphql.execute(RemoveCollectionPermission, RemoveCollectionPermission.Variables(input))
    }

    suspend fun setPublic(id: Uuid, public: Boolean) {
        network.boscaGraphql.execute(SetCollectionPublic, SetCollectionPublic.Variables(id, public))
    }

    suspend fun setPublicList(id: Uuid, ready: Boolean) {
        network.boscaGraphql.execute(SetCollectionPublicList, SetCollectionPublicList.Variables(id, ready))
    }

    suspend fun setReady(id: Uuid): Boolean =
        network.boscaGraphql.execute(SetCollectionReady, SetCollectionReady.Variables(id)).content.collection.setReady

    suspend fun delete(id: Uuid): Boolean =
        network.boscaGraphql.execute(DeleteCollection, DeleteCollection.Variables(id)).content.collection.delete

    suspend fun deletePermanently(id: Uuid) {
        network.boscaGraphql.execute(PermanentlyDeleteCollection, PermanentlyDeleteCollection.Variables(id))
    }

    suspend fun findCollections(attributes: List<FindAttributeInput>, offset: Int, limit: Int): List<FindCollectionsData.Content.FindCollections> =
        network.boscaGraphql.execute(
            FindCollections,
            FindCollections.Variables(FindQueryInput(attributes = listOf(FindAttributesInput(attributes)), offset = offset, limit = limit)),
        ).content.findCollections

    suspend fun findCollectionsBySystem(attributes: List<FindAttributeInput>, offset: Int, limit: Int): List<FindCollectionsBySystemData.Content.FindCollectionsBySystem> =
        network.boscaGraphql.execute(
            FindCollectionsBySystem,
            FindCollectionsBySystem.Variables(FindQueryInput(attributes = listOf(FindAttributesInput(attributes)), offset = offset, limit = limit)),
        ).content.findCollectionsBySystem

    suspend fun removeCollection(collectionId: Uuid, id: Uuid): Uuid? =
        network.boscaGraphql.execute(RemoveCollectionCollection, RemoveCollectionCollection.Variables(collectionId, id)).content.collection.removeChildCollection.id

    suspend fun addMetadata(collectionId: Uuid, metadataId: Uuid): Uuid? =
        network.boscaGraphql.execute(AddMetadataCollection, AddMetadataCollection.Variables(collectionId, metadataId)).content.collection.addChildMetadata.id

    suspend fun removeMetadata(collectionId: Uuid, metadataId: Uuid): Uuid? =
        network.boscaGraphql.execute(RemoveMetadataCollection, RemoveMetadataCollection.Variables(collectionId, metadataId)).content.collection.removeChildMetadata.id

    suspend fun mergeCollectionAttributes(collectionId: Uuid, attributes: JsonElement) {
        network.boscaGraphql.execute(MergeCollectionAttributes, MergeCollectionAttributes.Variables(collectionId, attributes))
    }

    suspend fun setLocked(collectionId: Uuid, locked: Boolean) {
        network.boscaGraphql.execute(SetCollectionLocked, SetCollectionLocked.Variables(collectionId, locked))
    }

    suspend fun mergeRelationshipAttributes(collectionId: Uuid, metadataId: Uuid, relationship: String, attributes: JsonElement) {
        network.boscaGraphql.execute(MergeCollectionRelationshipAttributes, MergeCollectionRelationshipAttributes.Variables(collectionId, metadataId, relationship, attributes))
    }

    suspend fun mergeCollectionItemAttributes(collectionId: Uuid, itemId: Uuid, attributes: JsonElement) {
        network.boscaGraphql.execute(MergeCollectionItemAttributes, MergeCollectionItemAttributes.Variables(collectionId, itemId, attributes))
    }

    suspend fun mergeMetadataItemAttributes(collectionId: Uuid, itemId: Uuid, attributes: JsonElement) {
        network.boscaGraphql.execute(MergeMetadataItemAttributes, MergeMetadataItemAttributes.Variables(collectionId, itemId, attributes))
    }

    suspend fun getSupplementaryContentDownload(supplementaryId: Uuid): GetCollectionSupplementaryDownloadData.Content.CollectionSupplementary.Content.Urls.Download? =
        network.boscaGraphql.execute(GetCollectionSupplementaryDownload, GetCollectionSupplementaryDownload.Variables(supplementaryId)).content.collectionSupplementary?.content?.urls?.download

    suspend fun setSupplementaryTextContent(supplementaryId: Uuid, contentType: String, content: String) {
        network.boscaGraphql.execute(
            SetCollectionSupplementaryTextContents,
            SetCollectionSupplementaryTextContents.Variables(supplementaryId = supplementaryId, contentType = contentType, content = content),
        )
    }

    suspend fun deleteSupplementary(supplementaryId: Uuid): Boolean =
        network.boscaGraphql.execute(DeleteSupplementaryCollection, DeleteSupplementaryCollection.Variables(supplementaryId)).content.collection.deleteSupplementary
}
