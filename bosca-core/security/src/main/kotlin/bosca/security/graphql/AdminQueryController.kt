package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.DuplicatedAccountIds
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

object AdminQuery

/** Admin-only read side of account administration (`security.admin.*`). */
@TypeController
class AdminQueryController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AdminQuery> {

    /** Lists groups of verified principals that share an email — i.e. duplicate accounts to merge. */
    @Field
    suspend fun duplicateAccounts(authentication: AuthenticationContext): List<DuplicatedAccountIds> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return securityService.findDuplicateAccounts()
    }
}
