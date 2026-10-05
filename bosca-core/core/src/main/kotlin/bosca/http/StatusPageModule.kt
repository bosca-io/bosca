package bosca.http

import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.pages.ErrorPage
import bosca.pages.NotFoundPage
import bosca.routes.toAPIError
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.HttpStatusCode
import bosca.server.middleware.StatusPagesMiddleware

/**
 * Installs the status pages middleware that provides custom error and not-found
 * page rendering, falling back to simple status responses when custom page
 * providers are not registered.
 */
class StatusPageModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) = with(application) {
        val notFoundPage = try {
            provide<NotFoundPage>()
        } catch (_: MissingProviderException) {
            null
        }
        val error = try {
            provide<ErrorPage>()
        } catch (_: MissingProviderException) {
            null
        }

        val builder = StatusPagesMiddleware.Builder()

        if (error != null) {
            builder.defaultException { call, exception ->
                log.error("Failed to process request: ${call.request.path}", exception)
                if (call.request.path.startsWith("/api") || call.request.contentType()?.match("application/json") == true) {
                    val response = exception.toAPIError()
                    call.respond(response.status, response)
                    return@defaultException
                }
                error.execute(call)
            }
        } else {
            builder.defaultException { call, exception ->
                log.error("Failed to process request: ${call.request.path}", exception)
                val response = exception.toAPIError()
                call.respond(response.status, response.message ?: response.status.description)
            }
        }

        if (notFoundPage != null) {
            builder.status(HttpStatusCode.NotFound) { call ->
                log.info("Not found: ${call.request.path}")
                if (call.request.path.startsWith("/api") ||
                    call.request.contentType()?.match("application/json") == true
                ) {
                    return@status
                }
                notFoundPage.execute(call)
            }
        }

        install(builder.build())
    }
}
