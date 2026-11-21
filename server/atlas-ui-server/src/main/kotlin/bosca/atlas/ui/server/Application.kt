package bosca.atlas.ui.server

import bosca.artifacts.ui.ArtifactsUi
import bosca.system.ui.SystemUi
import bosca.workops.ui.WorkOpsUi
import bosca.atlas.ui.AtlasUi
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.git.ui.GitUiNavigation
import bosca.atlas.ui.AtlasSubsystems
import bosca.git.ui.GitUi
import bosca.git.ui.installGitSubscriptions
import bosca.git.ui.server.installGitFileProxy
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine
import bosca.configuration.NatsModule
import bosca.configuration.RedisModule
import bosca.di.provide
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import kotlinx.serialization.json.Json

/** Starts Atlas's standalone BML listener against its configured HTTP API. */
fun main(args: Array<String>) {
    NettyServerEngine.start("atlas-ui-server.yaml", args) { module() }
}

/** Installs Atlas's imported applications, browser authentication, subscriptions and streamed file proxies. */
suspend fun BoscaApplication.module() {
    val endpoint = environment.config.property("bml.graphqlEndpoint").getString()
    val prefix = environment.config.propertyOrNull("bml.authCookiePrefix")?.getString()?.takeIf(String::isNotBlank)
    val pubSub = if (environment.config.propertyOrNull("pubsub.type")?.getString() == "redis") {
        if (environment.config.propertyOrNull("redis.host")?.getString()?.isNotBlank() == true) {
            install(RedisModule())
            RedisPubSubServiceImpl(Json, provide<RedisConnectionPool>())
        } else null
    } else if (environment.config.propertyOrNull("nats.url")?.getString()?.isNotBlank() == true) {
        install(NatsModule())
        NatsPubSubServiceImpl(Json, provide<NatsConnectionPool>())
    } else null
    installAtlasUiServer(endpoint, prefix, pubSub)
}

/** Uses the same UI implementation for embedded and standalone Atlas deployments. */
fun BoscaApplication.installAtlasUiServer(graphqlEndpoint: String, authCookiePrefix: String? = null, pubSub: PubSubService? = null) {
    val gitNavigation = GitUiNavigation(AtlasSubsystems.items, product = "Atlas")
    BmlServer(
        project = CompiledProject("atlas-ui", "0.0.1"),
        pages = AtlasUi.pages + GitUi.pages(gitNavigation).filterNot { it.route == "/" || it.route == "/login" || it.route.startsWith("/auth/") } + ArtifactsUi.pages + SystemUi.pages + WorkOpsUi.pages,
        components = (AtlasUi.components + GitUi.components + ArtifactsUi.components + SystemUi.components + WorkOpsUi.components).distinctBy { it.tag },
        componentRenderers = AtlasUi.componentRenderers + GitUi.componentRenderers(gitNavigation) + ArtifactsUi.componentRenderers + SystemUi.componentRenderers + WorkOpsUi.componentRenderers,
        islandDispatchers = AtlasUi.islandDispatchers + GitUi.islandDispatchers + ArtifactsUi.islandDispatchers + SystemUi.islandDispatchers + WorkOpsUi.islandDispatchers,
        componentIslandDispatchers = AtlasUi.componentIslandDispatchers + GitUi.componentIslandDispatchers + ArtifactsUi.componentIslandDispatchers + SystemUi.componentIslandDispatchers + WorkOpsUi.componentIslandDispatchers,
        contracts = GitUi.contracts + ArtifactsUi.contracts + SystemUi.contracts + WorkOpsUi.contracts(graphqlEndpoint),
        graphqlEndpoint = graphqlEndpoint,
        authCookiePrefix = authCookiePrefix,
        signInRoute = "/login",
        notFoundRoute = "/404",
        errorRoute = "/500",
        globalCss = AtlasUi.css + "\n" + GitUi.css + "\n" + ArtifactsUi.css + "\n" + SystemUi.css + "\n" + WorkOpsUi.css + "\n" +
            requireNotNull(ArtifactFileProxy::class.java.getResourceAsStream("/atlas-ui-server.css"))
                .use { it.readAllBytes().decodeToString() },
        globalJs = AtlasUi.javascript,
        dev = false,
    ).install(this)
    installGitSubscriptions(this, graphqlEndpoint)
    val artifactsEndpoint = environment.config.propertyOrNull("bml.artifactsEndpoint")?.getString()?.takeIf(String::isNotBlank)
        ?: System.getenv("BML_ARTIFACTS_ENDPOINT")?.takeIf(String::isNotBlank) ?: graphqlEndpoint
    ArtifactFileProxy(artifactsEndpoint, graphqlEndpoint).install(this)
    installGitFileProxy(graphqlEndpoint)
    DocumentCollaborationProxy(graphqlEndpoint).install(this)
    WorkOpsUi.install(this, graphqlEndpoint, pubSub)
    router.get("/welcome") { call.respondRedirect("/") }
    for (path in listOf("/favicon.svg", "/favicon.ico")) {
        router.get(path) { call.respondBytes(AtlasUi.favicon.encodeToByteArray(), bosca.server.ContentType.parse("image/svg+xml")) }
    }
    router.get("/api/v1/ready") { call.respond("ready") }
}
