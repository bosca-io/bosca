package bosca.bml.message.server

import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The BML Message Server: hosts published message-project jars —
 * fetch (digest-verified) → child-classloader load → render over the PRIVATE `/render` API,
 * hot-swapping on the registry's publish event with a poll backstop. A service image may carry
 * bundled versions for cold-start fallback. The ONLY public surface is the version-pinned asset
 * routes; deployments must not route `/render` or `/status` publicly.
 *
 * Configuration (env):
 *   BML_MESSAGE_ARTIFACTS_URL    artifacts registry origin, e.g. https://artifacts.example.com  (required)
 *   BML_MESSAGE_ARTIFACTS_TOKEN  registry token (raw pull on the bml-message namespace)
 *   BML_MESSAGE_PROJECTS         comma-separated seed projects; publish events add new ones automatically
 *   BML_MESSAGE_PUBLIC_URL       public base the asset URLs are minted from (default http://localhost:<port>)
 *   BML_MESSAGE_CACHE_DIR        jar cache directory (default ./message-cache)
 *   BML_MESSAGE_BUNDLED_PROJECTS_DIR packaged fallback jars (default ./bundled-message-projects)
 *   BML_MESSAGE_POLL_SECONDS     lazy version-check interval (default 60)
 *   BML_MESSAGE_RENDER_TIMEOUT_MS  per-render bound (default 10000)
 *   BML_MESSAGE_TRACKING_SECRET    enables first-party click/open tracking (link rewriting + /c /o routes)
 *   BML_GRAPHQL_ENDPOINT       Bosca GraphQL origin for template data and localized strings
 *                              (required; the platform-wide name every BML process uses)
 *   BML_MESSAGE_LOCALIZATION_PROJECT localization project (name or UUID) whose catalogs templates render with
 *   BML_MESSAGE_LOCALIZATION_TOKEN   service token for catalog reads
 *   BML_MESSAGE_LOCALIZATION_STATES  translation states to render (default PUBLISHED)
 *   BML_MESSAGE_LOCALIZATION_TTL_SECONDS catalog refresh cadence (default 300)
 *   BML_MESSAGE_ANALYTICS_URL      analytics collector origin — engagement events feed CTR reporting
 *   BML_MESSAGE_ANALYTICS_APP_ID / BML_MESSAGE_ANALYTICS_API_KEY  optional collector credentials
 *   NATS_URL / NATS_TOKEN  enables event-driven hot reload; absent = poll only
 *   PORT (or first arg)    listen port (default 9093)
 *   MANAGEMENT_PORT        optional liveness-only listener (`bosca.server.management-port`); absent = none
 */
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("bosca.bml.message.server")
    val port = args.firstOrNull()?.toIntOrNull() ?: System.getenv("PORT")?.toIntOrNull() ?: 9093
    val artifactsUrl = System.getenv("BML_MESSAGE_ARTIFACTS_URL")
        ?: error("BML_MESSAGE_ARTIFACTS_URL is required (the artifacts registry origin)")
    val graphqlEndpoint = requiredGraphqlEndpoint()
    val token = System.getenv("BML_MESSAGE_ARTIFACTS_TOKEN")
    val seeds = System.getenv("BML_MESSAGE_PROJECTS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val publicBaseUrl = System.getenv("BML_MESSAGE_PUBLIC_URL") ?: "http://localhost:$port"
    val cacheDir = File(System.getenv("BML_MESSAGE_CACHE_DIR") ?: "message-cache")
    val bundled = BundledMessageProjects(File(System.getenv("BML_MESSAGE_BUNDLED_PROJECTS_DIR") ?: "bundled-message-projects"))
    val pollInterval = (System.getenv("BML_MESSAGE_POLL_SECONDS")?.toLongOrNull() ?: 60L).seconds
    val renderTimeout = (System.getenv("BML_MESSAGE_RENDER_TIMEOUT_MS")?.toLongOrNull() ?: 10_000L).milliseconds
    val tracking = System.getenv("BML_MESSAGE_TRACKING_SECRET")?.takeIf { it.isNotBlank() }
        ?.let { LinkTracking(it, publicBaseUrl) }
    val analytics = System.getenv("BML_MESSAGE_ANALYTICS_URL")?.takeIf { it.isNotBlank() }?.let {
        bosca.analytics.server.HttpServerAnalyticsClient(
            it,
            appId = System.getenv("BML_MESSAGE_ANALYTICS_APP_ID"),
            apiKey = System.getenv("BML_MESSAGE_ANALYTICS_API_KEY"),
        )
    }

    // Localized strings: binding a Bosca localization project gives every rendered
    // channel t()/t-markup catalogs under the recipient's locale. Unset = unlocalized (unchanged).
    val localization = System.getenv("BML_MESSAGE_LOCALIZATION_PROJECT")
        ?.takeIf { it.isNotBlank() }
        ?.let { project ->
            bosca.bml.i18n.BmlLocalization(
                bosca.bml.i18n.GraphQLLocalizationClient(
                    gql = bosca.bml.graphql.HttpGraphQLClient(graphqlEndpoint),
                    project = project,
                    token = System.getenv("BML_MESSAGE_LOCALIZATION_TOKEN"),
                    states = System.getenv("BML_MESSAGE_LOCALIZATION_STATES")
                        ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                        ?: setOf("PUBLISHED"),
                ),
                ttl = (System.getenv("BML_MESSAGE_LOCALIZATION_TTL_SECONDS")?.toLongOrNull() ?: 300L).seconds,
            )
        }

    val client = MessageArtifactClient(artifactsUrl, token)
    val cache = MessageJarCache(cacheDir, client, bundled)
    val projects = MessageProjects(cache, Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader())

    // Event-driven reload when NATS is configured; the poll backstop runs either way.
    val pubSub: PubSubService? = System.getenv("NATS_URL")?.let { url ->
        NatsPubSubServiceImpl(Json, NatsConnectionPool(url, System.getenv("NATS_TOKEN").orEmpty()))
    }
    val reloader = MessageReloader(projects, client, seeds, pollInterval, pubSub, bundled)

    val managementPort = System.getenv("MANAGEMENT_PORT")?.takeIf { it.isNotBlank() }?.let { raw ->
        raw.trim().toIntOrNull().also { if (it == null) log.warn("Ignoring MANAGEMENT_PORT={}: not a port number", raw) }
    }
    val config = ApplicationConfig.load(
        buildString {
            append("bosca:\n  server:\n    port: $port\n    development: false\n")
            if (managementPort != null) append("    management-port: $managementPort\n")
        }.byteInputStream(Charsets.UTF_8),
    )
    val application = BoscaApplication(config)
    RenderApi.install(
        application, projects, cache, publicBaseUrl, renderTimeout, tracking,
        graphqlClientFactory = bosca.bml.graphql.GraphQLClientFactory(graphqlEndpoint),
        messageSource = localization ?: bosca.bml.i18n.MessageSource.Empty,
    )
    AssetRoutes.install(application, cache)
    ProjectsApi.install(application, projects, client, bundled)
    if (tracking != null) TrackingRoutes.install(application, tracking, pubSub, analytics)
    HealthApi.install(application, projects, reloader)
    application.freezeMiddleware()

    runBlocking { reloader.warmup() }
    reloader.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("bml-message-server stopping")
            projects.close()
            Runtime.getRuntime().halt(0)
        },
    )
    log.info(
        "bml-message-server on http://localhost:{} — registry {}, {} project(s): {}, reload: {}",
        port, artifactsUrl, projects.projects.size, projects.projects.sorted(),
        if (pubSub != null) "event + ${pollInterval} poll" else "poll every $pollInterval",
    )
    NettyServerEngine(application, port).start()
}

internal fun requiredGraphqlEndpoint(
    environment: (String) -> String? = System::getenv,
): String = environment("BML_GRAPHQL_ENDPOINT")
    ?.takeIf { it.isNotBlank() }
    ?: error("BML_GRAPHQL_ENDPOINT is required (the Bosca GraphQL API endpoint)")
