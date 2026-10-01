package bosca.http

import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.middleware.CorsConfiguration
import bosca.server.middleware.CorsMiddleware
import bosca.server.middleware.DefaultHeadersMiddleware
import org.slf4j.LoggerFactory

/**
 * Registers the HTTP client DI provider and installs CORS and default header
 * middleware for cross-origin request handling and standard response headers.
 */
class HttpModule : BoscaApplicationModule {

    private val log = LoggerFactory.getLogger(HttpModule::class.java)

    override suspend fun install(application: BoscaApplication) = with(application) {
        provides { Client() }

        install(DefaultHeadersMiddleware(mapOf("X-Engine" to "Bosca")))

        val corsYaml = application.config.propertyOrNull("cors")?.getAs<CorsConfiguration>()
        val corsConfig = corsYaml?.toCorsConfig(application.developmentMode)
        if (corsConfig != null) {
            install(CorsMiddleware(corsConfig))
        } else {
            log.info("CORS not configured, skipping CORS middleware installation")
        }
    }
}
