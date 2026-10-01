package bosca.profile.profile.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.hubspot.transformer.HubSpotEntityTransformer
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class ThirdPartyExtension(val profileId: UUID)

@TypeController
class ThirdPartyExtensionController(
    private val groupEvaluator: GroupEvaluator,
    private val hubSpotEntityTransformer: HubSpotEntityTransformer
) : GraphQLController<ThirdPartyExtension> {

    @Field
    suspend fun context(authenticationContext: AuthenticationContext, extension: ThirdPartyExtension): JsonElement {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        return hubSpotEntityTransformer.transform(Unit, extension.profileId).context
    }
}