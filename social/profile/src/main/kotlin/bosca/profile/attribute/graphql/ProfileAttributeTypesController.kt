package bosca.profile.attribute.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object ProfileAttributeTypes

@TypeController
class ProfileAttributeTypesController(
    private val groupEvaluator: GroupEvaluator,
    private val service: ProfileAttributeService
) : GraphQLController<ProfileAttributeTypes> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<ProfileAttributeType> {
        val allTypes = service.getAllAttributeTypes()
        if (!groupEvaluator.hasAdminGroup(authentication)) {
            return allTypes.filter { it.visibility != ProfileVisibility.SYSTEM }
        }
        return allTypes
    }
}
