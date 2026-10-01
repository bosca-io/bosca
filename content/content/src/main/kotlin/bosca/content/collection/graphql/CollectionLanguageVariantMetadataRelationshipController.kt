package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class CollectionLanguageVariantMetadataRelationshipController(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<CollectionLanguageVariantMetadataRelationship> {

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, relationship: CollectionLanguageVariantMetadataRelationship): Metadata {
        val metadata = metadataService.getById(relationship.metadataId) ?: throw NoSuchElementException("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }

    @Field
    fun relationship(relationship: CollectionLanguageVariantMetadataRelationship) = relationship.relationship

    @Field
    fun languageTag(relationship: CollectionLanguageVariantMetadataRelationship) = relationship.languageTag

    @Field
    fun attributes(relationship: CollectionLanguageVariantMetadataRelationship) = relationship.attributes
}
