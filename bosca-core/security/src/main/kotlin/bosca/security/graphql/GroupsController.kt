package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.security.service.AuthenticationContext


object Groups

@TypeController
class GroupsController(
    private val security: SecurityService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Groups> {

    @Field
    suspend fun all(authorization: AuthenticationContext, type: GroupType?, limit: Int, offset: Long): List<Group> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return security.getGroups(type, offset, limit)
    }

    @Field
    suspend fun find(authorization: AuthenticationContext, nameOrDescription: String, type: GroupType?, limit: Int, offset: Long): List<Group> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return security.findGroups(nameOrDescription, type, offset, limit)
    }
}