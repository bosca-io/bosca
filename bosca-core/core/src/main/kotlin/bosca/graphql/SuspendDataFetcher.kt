package bosca.graphql

import bosca.db.withConnectionManager
import bosca.graphql.server.FieldResolver
import bosca.graphql.server.ResolverContext
import bosca.security.service.AuthenticationContext
import bosca.telemetry.withSpan
import io.opentelemetry.api.trace.Tracer

class SuspendDataFetcher<T>(
    private val name: String,
    private val tracer: Tracer,
    private val block: suspend (environment: ResolverContext, authenticationContext: AuthenticationContext) -> T,
) : FieldResolver {

    override suspend fun resolve(context: ResolverContext): T {
        val authenticationContext = context.context.getAs<AuthenticationContext>("authenticationContext")
            ?: error("Missing authenticationContext")
        return tracer.withSpan("field $name") {
            withConnectionManager {
                block(context, authenticationContext)
            }
        }
    }
}
