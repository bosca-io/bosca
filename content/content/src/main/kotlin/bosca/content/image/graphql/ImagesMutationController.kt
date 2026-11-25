package bosca.content.image.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.image.model.Coordinates
import bosca.content.image.model.ImageAttachment
import bosca.content.image.service.ImageService
import bosca.content.image.service.ImageGenerationService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class ImagesMutationController(
    private val images: ImageGenerationService,
    private val attachments: ImageService,
    private val metadata: MetadataService,
    private val collections: CollectionService,
    private val metadataPermissions: MetadataPermissionEvaluator,
    private val collectionPermissions: CollectionPermissionEvaluator,
    private val groups: GroupEvaluator,
) : GraphQLController<ImagesMutation> {
    @Field
    suspend fun generate(authentication: AuthenticationContext, prompt: String, name: String, parentCollectionId: UUID?): Metadata {
        val parent = destination(authentication, parentCollectionId)
        return images.generate(prompt, name, null, parent)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, sourceId: UUID, prompt: String, name: String, parentCollectionId: UUID?): Metadata {
        val parent = destination(authentication, parentCollectionId)
        val source = metadata.getById(sourceId) ?: error("Source image not found")
        metadataPermissions.verifyContentAllowed(authentication, source, PermissionAction.VIEW)
        return images.generate(prompt, name, source, parent)
    }

    @Field
    suspend fun attach(
        authentication: AuthenticationContext, imageMetadataId: UUID,
        targetMetadataId: UUID?, targetCollectionId: UUID?, targetLanguageTag: String?,
        attributeKey: String?, crop: Coordinates?,
    ): ImageAttachment {
        require((targetMetadataId == null) != (targetCollectionId == null)) { "Set exactly one attachment target" }
        val image = metadata.getById(imageMetadataId) ?: error("Image metadata not found")
        metadataPermissions.verifyContentAllowed(authentication, image, PermissionAction.VIEW)
        if (targetMetadataId != null) {
            require(targetLanguageTag.isNullOrBlank()) { "targetLanguageTag is only valid with targetCollectionId" }
            val target = metadata.getById(targetMetadataId) ?: error("Target metadata not found")
            metadataPermissions.verifyAllowed(authentication, target, PermissionAction.EDIT)
            return attachments.attach(image, target, attributeKey, crop)
        }
        val target = collections.getById(requireNotNull(targetCollectionId)) ?: error("Target collection not found")
        val variant = targetLanguageTag?.takeIf { it.isNotBlank() && it != target.languageTag }?.let {
            collections.getLanguageVariant(target.id, it) ?: error("Collection language variant not found")
        }
        collectionPermissions.verifyAllowed(authentication, variant ?: target, PermissionAction.EDIT)
        return attachments.attach(image, target, targetLanguageTag, attributeKey, crop)
    }

    private suspend fun destination(authentication: AuthenticationContext, id: UUID?): Collection? {
        groups.verifyHasScope(authentication, ApiTokenScopes.CONTENT_EDIT.name)
        if (id == null) {
            groups.verifyHasEditorGroup(authentication)
            return null
        }
        val parent = collections.getById(id) ?: error("Destination collection not found")
        collectionPermissions.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        return parent
    }
}
