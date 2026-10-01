package bosca.analytics.server

import bosca.analytics.service.EventProcessingService
import bosca.di.provide
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import org.slf4j.LoggerFactory


/**
 * Application module that wires the [ServerAnalyticsClient] DI binding
 * and installs [AnalyticsMiddleware] for handler context propagation and error capture.
 *
 * The client implementation is chosen based on the application's
 * configuration:
 *
 * - When an `iceberg` configuration block is present, the server hosts
 *   the analytics pipeline in-process and an
 *   [InProcessServerAnalyticsClient] is used (zero network overhead).
 * - When no `iceberg` configuration is found, an
 *   [HttpServerAnalyticsClient] forwards events to a remote analytics
 *   server whose URL is read from `analyticsServer.url`.
 *
 * The `analyticsServer.appId` configuration property identifies this
 * server in error events and is attached to every captured exception.
 *
 * Install this from `Application.module()` after the analytics
 * provider registrar has run:
 *
 * ```
 * install(AnalyticsServerClientModule())
 * ```
 */
class AnalyticsServerClientModule(
    /**
     * Allowlist of request headers that the middleware will attach to
     * captured error events. Defaults match
     * [AnalyticsMiddleware.DEFAULT_HEADER_ALLOW_LIST]. Cookies
     * and authorization headers are deliberately excluded.
     */
    private val headerAllowList: Set<String> = AnalyticsMiddleware.DEFAULT_HEADER_ALLOW_LIST,
) : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication): Unit = with(application) {
        val appId = environment.config.property("analyticsServer.appId").getString()
        val hasIceberg = environment.config.propertyOrNull("iceberg") != null
        val client: ServerAnalyticsClient = if (hasIceberg) {
            val eventProcessingService = provide<EventProcessingService>()
            InProcessServerAnalyticsClient(eventProcessingService, appId = appId).also {
                log.info("Using in-process analytics client (iceberg configured, appId={})", appId)
            }
        } else {
            val url = environment.config.property("analyticsServer.url").getString()
            HttpServerAnalyticsClient(baseUrl = url, appId = appId).also {
                log.info("Using HTTP analytics client -> {} (appId={})", url, appId)
            }
        }
        provides(singleton = true) { client }

        val errorCapture = ErrorCapture { throwable, call, context ->
            val merged = AnalyticsErrorContextResolver.resolve(
                call = call,
                ambient = analyticsContext().toMap(),
                explicit = context,
            )
            client.captureException(
                throwable = throwable,
                fatal = call?.let { isFatal(it) } ?: false,
                appId = appId,
                sessionId = merged["session_id"] as? String,
                userId = call?.let { serverCall ->
                    serverCall.authenticationContext.principal()?.let { it.id.toString() }
                },
                context = merged,
            )
        }
        provides<ErrorCapture>(singleton = true) { errorCapture }

        install(AnalyticsMiddleware(client = client, appId = appId, headerAllowList = headerAllowList))
        onShutdown {
            log.info("Flushing analytics client...")
            client.flush()
            if (client is AutoCloseable) client.close()
            log.info("Analytics client flushed")
        }
    }

    private fun isFatal(call: ServerCall): Boolean {
        val status = call.response.status() ?: return true
        return status.value >= HttpStatusCode.InternalServerError.value
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnalyticsServerClientModule::class.java)
    }
}
