package bosca.git.ui

import bml.generated.BmlComponents
import bml.generated.BmlIslands
import bml.generated.BmlPages
import bosca.atlas.ui.AtlasUi
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication

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
        pages = AtlasUi.pages + BmlPages.all,
        components = (AtlasUi.components + BmlComponents.all).distinctBy { it.tag },
        componentRenderers = AtlasUi.componentRenderers + BmlComponents.renderers,
        islandDispatchers = AtlasUi.islandDispatchers + BmlIslands.dispatchers,
        componentIslandDispatchers = AtlasUi.componentIslandDispatchers + BmlIslands.componentDispatchers,
        contracts =
            listOf(
                bml.generated.GitOwnerSearchDispatcher(OwnerSearch()),
                bml.generated.GitRefSearchDispatcher(RefSearch()),
                bml.generated.GitMarkdownPreviewDispatcher(MarkdownPreview()),
            ),
        graphqlEndpoint = graphqlEndpoint,
        authCookiePrefix = authCookiePrefix,
        signInRoute = "/login",
        notFoundRoute = "/404",
        errorRoute = "/500",
        globalCss = AtlasUi.css + "\n" + resource("git-ui.css"),
        globalJs = AtlasUi.javascript,
        dev = false,
    )

private fun resource(name: String): String =
    requireNotNull(GitUiAssets::class.java.getResourceAsStream("/$name")) {
            "Git UI resource missing: $name"
        }
        .use { it.readAllBytes().decodeToString() }
