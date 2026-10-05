package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID


object GroupsMutation

@TypeController
class GroupsMutationController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<GroupsMutation> {

    @Field
    suspend fun addGroup(
        authentication: AuthenticationContext,
        name: String,
        description: String,
        groupType: GroupType
    ): Group {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return securityService.addGroup(Group(UUID.NIL, name, description, groupType))
    }

    @Field
    suspend fun deleteGroup(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        securityService.deleteGroup(id)
        return true
    }

    @Field
    suspend fun editGroup(
        authentication: AuthenticationContext,
        id: UUID,
        name: String,
        description: String,
        groupType: GroupType
    ): Group {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return securityService.editGroup(Group(id, name, description, groupType))
    }
}