package bosca.profile.attribute.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

@TypeController
class ProfileAttributeController(
    private val service: ProfileAttributeService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator
) : GraphQLController<ProfileAttribute> {

    @Field
    fun id(attribute: ProfileAttribute): UUID {
        return attribute.id
    }

    @Field
    fun typeId(attribute: ProfileAttribute) = attribute.typeId

    @Field
    suspend fun type(attribute: ProfileAttribute) = service.getAttributeTypeById(attribute.typeId)

    @Field
    fun source(attribute: ProfileAttribute) = attribute.source

    @Field
    fun priority(attribute: ProfileAttribute) = attribute.priority

    @Field
    fun attributes(attribute: ProfileAttribute) = attribute.attributes

    @Field
    fun confidence(attribute: ProfileAttribute) = attribute.confidence

    @Field
    suspend fun metadata(
        authentication: AuthenticationContext,
        attribute: ProfileAttribute
    ): Metadata? {
        return attribute.metadataId?.let {
            metadataService.getById(it)?.takeIf { metadataPermissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW) }
        }
    }

    @Field
    fun created(attribute: ProfileAttribute) = attribute.created

    @Field
    fun expires(attribute: ProfileAttribute) = attribute.expires

    @Field
    fun visibility(attribute: ProfileAttribute) = attribute.visibility

    @Field
    fun verified(attribute: ProfileAttribute) = attribute.verified

    @Field
    fun verificationSource(attribute: ProfileAttribute) = attribute.verificationSource

    // NOTE: deliberately NO resolver for `verificationToken` — it is internal and must never be exposed.
}