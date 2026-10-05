package bosca.graphql

import bosca.security.service.AuthenticationContext
import bosca.graphql.server.FieldResolver
import bosca.graphql.server.ResolverContext
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

class PropertyDataFetcher<T>(
    private val name: String,
    private val tracer: Tracer,
    private val block: (environment: ResolverContext, authenticationContext: AuthenticationContext) -> T
) : FieldResolver {

    override suspend fun resolve(context: ResolverContext): T {
        val authenticationContext = context.context.getAs<AuthenticationContext>("authenticationContext")
            ?: error("Missing authenticationContext")
        val span = tracer
            .spanBuilder("field $name")
            .setParent(Context.current())
            .startSpan()
        try {
            span.makeCurrent().use {
                return block(context, authenticationContext)
            }
        } finally {
            span.end()
        }
    }
}
