package bosca.git.ui

import bml.generated.BmlComponents
import bml.generated.BmlIslands
import bml.generated.BmlPages
import bosca.bml.auth.AuthUi
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlBundledPage
import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.RenderContext
import kotlinx.coroutines.withContext
import bosca.server.BoscaApplication

/** Compiled Git pages and assets imported by standalone and composed hosts. */
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

    /** Binds host navigation to full-page rendering while retaining the compiled page metadata. */
    fun pages(navigation: GitUiNavigation): List<BmlPageRenderer> = pages.map { page ->
        object : BmlPageRenderer by page, BmlBundledPage {
            override val assets = (page as BmlBundledPage).assets
            override val renderRevision: String = page.renderRevision + ":" + navigation
            override suspend fun render(ctx: RenderContext) = withContext(navigation) { page.render(ctx) }
        }
    }

    /** Binds the same navigation when the host renders a component directly. */
    fun componentRenderers(navigation: GitUiNavigation): Map<String, BmlComponentRenderer> =
        componentRenderers.mapValues { (_, renderer) ->
            object : BmlComponentRenderer by renderer {
                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>, slot: suspend () -> Unit) =
                    withContext(navigation) { renderer.render(ctx, props, slot) }
            }
        }
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
        pages = GitUi.pages,
        components = GitUi.components,
        componentRenderers = GitUi.componentRenderers,
        islandDispatchers = GitUi.islandDispatchers,
        componentIslandDispatchers = GitUi.componentIslandDispatchers,
        contracts = GitUi.contracts,
        graphqlEndpoint = graphqlEndpoint,
        authCookiePrefix = authCookiePrefix,
        signInRoute = "/login",
        notFoundRoute = "/404",
        errorRoute = "/500",
        globalCss = AuthUi.css + "\n" + GitUi.css,
        globalJs = resource("git-ui.js"),
        dev = false,
    )

private fun resource(name: String): String =
    requireNotNull(GitUiAssets::class.java.getResourceAsStream("/$name")) {
            "Git UI resource missing: $name"
        }
        .use { it.readAllBytes().decodeToString() }
