package bosca.graphql

import bosca.graphql.server.FieldResolver
import bosca.graphql.server.ResolverContext
import bosca.security.service.AuthenticationContext
import bosca.telemetry.withSyncSpan
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.Flow

class FlowDataFetcher<T : Any>(
    private val name: String,
    private val tracer: Tracer,
    private val block: (environment: ResolverContext, authenticationContext: AuthenticationContext) -> Flow<T>,
) : FieldResolver {

    override suspend fun resolve(context: ResolverContext): Flow<T> {
        val authenticationContext = context.context.getAs<AuthenticationContext>("authenticationContext")
            ?: error("Missing authenticationContext")
        return tracer.withSyncSpan("field $name") {
            block(context, authenticationContext)
        }
    }
}
