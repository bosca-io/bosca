package bosca.git.ui

import bml.generated.BmlComponents
import bml.generated.BmlIslands
import bml.generated.BmlPages
import bosca.atlas.ui.AtlasUi
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication

/** Compiled Git pages and assets imported by standalone Git and Atlas hosts. */
object GitUi {
    val pages = BmlPages.all
    val components = BmlComponents.all
    val componentRenderers = BmlComponents.renderers
    val islandDispatchers = BmlIslands.dispatchers
    val componentIslandDispatchers = BmlIslands.componentDispatchers
    val contracts get() = listOf(
        bml.generated.GitOwnerSearchDispatcher(OwnerSearch()),
        bml.generated.GitRefSearchDispatcher(RefSearch()),
        bml.generated.GitMarkdownPreviewDispatcher(MarkdownPreview()),
    )
    val css: String get() = resource("git-ui.css")
}

/** Installs the compiled Git UI and its packaged client assets into an existing server. */
fun installGitUi(
    application: BoscaApplication,
    graphqlEndpoint: String,
    authCookiePrefix: String? = null,
) {
    gitUiServer(graphqlEndpoint, authCookiePrefix).install(application)
    installGitSubscriptions(application, graphqlEndpoint)
}

/** Builds an embeddable BML instance; the host owns its HTTP engine and lifecycle. */
fun gitUiServer(graphqlEndpoint: String?, authCookiePrefix: String? = null): BmlServer =
    BmlServer(
        project = CompiledProject(name = "git-ui", version = "0.0.1"),
        pages = AtlasUi.pages.filterNot { it.route == "/" } + GitUi.pages,
        components = (AtlasUi.components + GitUi.components).distinctBy { it.tag },
        componentRenderers = AtlasUi.componentRenderers + GitUi.componentRenderers,
        islandDispatchers = AtlasUi.islandDispatchers + GitUi.islandDispatchers,
        componentIslandDispatchers = AtlasUi.componentIslandDispatchers + GitUi.componentIslandDispatchers,
        contracts = GitUi.contracts,
        graphqlEndpoint = graphqlEndpoint,
        authCookiePrefix = authCookiePrefix,
        signInRoute = "/login",
        notFoundRoute = "/404",
        errorRoute = "/500",
        globalCss = AtlasUi.css + "\n" + GitUi.css,
        globalJs = AtlasUi.javascript,
        dev = false,
    )

private fun resource(name: String): String =
    requireNotNull(GitUiAssets::class.java.getResourceAsStream("/$name")) {
            "Git UI resource missing: $name"
        }
        .use { it.readAllBytes().decodeToString() }
