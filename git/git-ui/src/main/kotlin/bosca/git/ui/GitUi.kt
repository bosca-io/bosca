package bosca.git.ui

import bml.generated.BmlComponents
import bml.generated.BmlIslands
import bml.generated.BmlPages
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installs the compiled Git UI and its packaged client assets into an existing server. */
fun installGitUi(
    application: BoscaApplication,
    graphqlEndpoint: String,
    authCookiePrefix: String? = null,
) {
    gitUiServer(graphqlEndpoint, authCookiePrefix).install(application)
    installGitSubscriptions(application, graphqlEndpoint)
    installClientAssets(application)
}

private fun installClientAssets(application: BoscaApplication) {
    application.router.get("/_git/fonts/{path}") {
        withContext(Dispatchers.IO) {
            val path = call.pathParameters["path"].orEmpty()
            val bytes =
                if (FONT_ASSET_NAME.matches(path)) {
                    GitUiAssets::class.java.getResourceAsStream("/git-ui/fonts/$path")?.use {
                        it.readAllBytes()
                    }
                } else null
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.response.header("Cache-Control", "public, max-age=31536000, immutable")
                call.respondBytes(bytes, ContentType("font", "woff2"))
            }
        }
    }
    application.router.get("/_bml/js/{path}") {
        withContext(Dispatchers.IO) {
            val path = call.pathParameters["path"].orEmpty()
            val bytes =
                if (CLIENT_ASSET_NAME.matches(path)) {
                    GitUiAssets::class.java.getResourceAsStream("/git-ui/js/$path")?.use {
                        it.readAllBytes()
                    }
                } else null
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.response.header("Cache-Control", "public, max-age=0, must-revalidate")
                call.respondBytes(bytes, ContentType.Text.JavaScript)
            }
        }
    }
}

/** Builds an embeddable BML instance; the host owns its HTTP engine and lifecycle. */
fun gitUiServer(graphqlEndpoint: String?, authCookiePrefix: String? = null): BmlServer =
    BmlServer(
        project = CompiledProject(name = "git-ui", version = "0.0.1"),
        pages = BmlPages.all,
        components = BmlComponents.all,
        componentRenderers = BmlComponents.renderers,
        islandDispatchers = BmlIslands.dispatchers,
        componentIslandDispatchers = BmlIslands.componentDispatchers,
        contracts =
            listOf(
                bml.generated.GitOwnerSearchDispatcher(OwnerSearch()),
                bml.generated.GitMarkdownPreviewDispatcher(MarkdownPreview()),
            ),
        graphqlEndpoint = graphqlEndpoint,
        authCookiePrefix = authCookiePrefix,
        signInRoute = "/login",
        notFoundRoute = "/404",
        errorRoute = "/500",
        globalCss = resource("git-ui-fonts.css") + "\n" + resource("git-ui.css"),
        globalJs = resource("git-ui.js"),
        dev = false,
    )

private val CLIENT_ASSET_NAME = Regex("[A-Za-z0-9_-]+\\.page\\.js")
private val FONT_ASSET_NAME = Regex("[A-Za-z0-9_-]+\\.woff2")

private fun resource(name: String): String =
    requireNotNull(GitUiAssets::class.java.getResourceAsStream("/$name")) {
            "Git UI resource missing: $name"
        }
        .use { it.readAllBytes().decodeToString() }
