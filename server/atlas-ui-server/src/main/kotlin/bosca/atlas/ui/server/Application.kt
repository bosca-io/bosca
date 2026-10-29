package bosca.atlas.ui.server

import bosca.artifacts.ui.ArtifactsUi
import bosca.atlas.ui.AtlasUi
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.git.ui.GitUi
import bosca.git.ui.installGitSubscriptions
import bosca.git.ui.server.installGitFileProxy
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine

/** Starts Atlas's standalone BML listener against its configured HTTP API. */
fun main(args: Array<String>) {
    NettyServerEngine.start("atlas-ui-server.yaml", args) { module() }
}

/** Installs Atlas and Git pages, browser authentication, subscriptions, and streamed file proxies. */
fun BoscaApplication.module() {
    val endpoint = environment.config.property("bml.graphqlEndpoint").getString()
    val prefix = environment.config.propertyOrNull("bml.authCookiePrefix")?.getString()?.takeIf(String::isNotBlank)
    installAtlasUiServer(endpoint, prefix)
}

/** Uses the same UI implementation for embedded and standalone Atlas deployments. */
fun BoscaApplication.installAtlasUiServer(graphqlEndpoint: String, authCookiePrefix: String? = null) {
    BmlServer(
        project = CompiledProject("atlas-ui", "0.0.1"),
        pages = AtlasUi.pages + GitUi.pages.filterNot { it.route == "/" } + ArtifactsUi.pages,
        components = (AtlasUi.components + GitUi.components + ArtifactsUi.components).distinctBy { it.tag },
        componentRenderers = AtlasUi.componentRenderers + GitUi.componentRenderers + ArtifactsUi.componentRenderers,
        islandDispatchers = AtlasUi.islandDispatchers + GitUi.islandDispatchers + ArtifactsUi.islandDispatchers,
        componentIslandDispatchers = AtlasUi.componentIslandDispatchers + GitUi.componentIslandDispatchers + ArtifactsUi.componentIslandDispatchers,
        contracts = GitUi.contracts + ArtifactsUi.contracts,
        graphqlEndpoint = graphqlEndpoint,
        authCookiePrefix = authCookiePrefix,
        signInRoute = "/login",
        notFoundRoute = "/404",
        errorRoute = "/500",
        globalCss = AtlasUi.css + "\n" + GitUi.css + "\n" + ArtifactsUi.css + "\n" +
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
    router.get("/welcome") { call.respondRedirect("/") }
    for (path in listOf("/favicon.svg", "/favicon.ico")) {
        router.get(path) { call.respondBytes(AtlasUi.favicon.encodeToByteArray(), bosca.server.ContentType.parse("image/svg+xml")) }
    }
    router.get("/api/v1/ready") { call.respond("ready") }
}
