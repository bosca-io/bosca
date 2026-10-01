package bosca.security.service

import bosca.graphql.server.GraphQLException

open class SecurityException(override val message: String): GraphQLException(message)

abstract class SecurityEvaluator {

    protected abstract val securityService: SecurityService
    protected abstract val groupEvaluator: GroupEvaluator

    fun throwUnauthorized() {
        throw SecurityException("Unauthorized access")
    }
}
