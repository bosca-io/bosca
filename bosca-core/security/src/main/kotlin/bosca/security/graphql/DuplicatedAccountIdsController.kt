package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.DuplicatedAccountIds
import bosca.security.model.Principal
import bosca.security.service.SecurityService

@TypeController
class DuplicatedAccountIdsController(
    private val securityService: SecurityService,
) : GraphQLController<DuplicatedAccountIds> {

    @Field
    fun email(group: DuplicatedAccountIds) = group.email

    @Field
    suspend fun principals(group: DuplicatedAccountIds): List<Principal> =
        securityService.getPrincipalsById(group.principalIds)
}
