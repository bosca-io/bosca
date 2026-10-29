package bosca.atlas.ui

import bosca.atlas.ui.generated.BmlComponents
import bosca.atlas.ui.generated.BmlIslands
import bosca.atlas.ui.generated.BmlPages
import bosca.bml.auth.AuthUi
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication

/** Shared Atlas pages, authentication, and assets composed by application hosts. */
object AtlasUi {
    val pages = BmlPages.all
    val components = BmlComponents.all
    val componentRenderers = BmlComponents.renderers
    val islandDispatchers = BmlIslands.dispatchers
    val componentIslandDispatchers = BmlIslands.componentDispatchers
    val css: String get() = AuthUi.css + "\n" + resource("atlas-ui.css")
    val javascript: String get() = resource("atlas-ui.js")
    val favicon: String get() = resource("atlas-mark.svg")

    /** Builds the shared UI using the caller's configured HTTP GraphQL endpoint and cookie prefix. */
    fun server(graphqlEndpoint: String?, authCookiePrefix: String? = null): BmlServer =
        BmlServer(
            project = CompiledProject(name = "atlas-ui", version = "0.0.1"),
            pages = pages,
            components = components,
            componentRenderers = componentRenderers,
            islandDispatchers = islandDispatchers,
            componentIslandDispatchers = componentIslandDispatchers,
            graphqlEndpoint = graphqlEndpoint,
            authCookiePrefix = authCookiePrefix,
            signInRoute = "/login",
            globalCss = css,
            globalJs = javascript,
            dev = false,
        )

    /** Installs shared Atlas routes and their packaged assets into a host application. */
    fun install(application: BoscaApplication, graphqlEndpoint: String, authCookiePrefix: String? = null) {
        server(graphqlEndpoint, authCookiePrefix).install(application)
        application.router.get("/favicon.svg") {
            call.respondBytes(favicon.encodeToByteArray(), bosca.server.ContentType.parse("image/svg+xml"))
        }
    }

    private fun resource(name: String): String =
        requireNotNull(AtlasUi::class.java.getResourceAsStream("/$name")) {
            "Atlas UI resource missing: $name"
        }.use { it.readAllBytes().decodeToString() }
}
