package bosca.messages.routes

import bosca.routes.configureMessagesPagesPageRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.HttpStatusCode
import bosca.server.middleware.StatusPagesMiddleware
import java.io.File

/**
 * Configures static resource serving, page routes, and error handling for the messages
 * pages module. In development mode, static files are served from the project source tree
 * for hot-reload convenience. A [StatusPagesMiddleware] is installed to handle
 * [SecurityException] by returning a 401 Unauthorized response.
 */
class MessagesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) = with(application) {
        routing {
            if (developmentMode) {
                staticFiles("/", File("pages/messages-pages/src/main/resources/static"))
            } else {
                staticResources("/", "static")
            }
        }
        configureMessagesPagesPageRoutes()

        install(StatusPagesMiddleware.Builder().apply {
            exception<Throwable> { call, cause ->
                if (cause is SecurityException) {
                    call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                }
            }
        }.build())
    }
}
