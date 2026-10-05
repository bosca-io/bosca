package bosca.notifications.web

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import java.io.File

/**
 * Runs the email preferences / unsubscribe site on the dedicated SSR server (bml-server):
 *
 *     ./gradlew :notifications-web:run          # -> http://localhost:9094/
 *
 * Pages/components come from the compiler-generated `bml.generated.*` registries. Data is live
 * Bosca GraphQL at `BML_GRAPHQL_ENDPOINT` (default `http://localhost:8080/graphql`) —
 * every operation is token-scoped (the unsubscribe token minted into email footers), so the
 * site is fully anonymous: no auth SDK or sign-in. Declarative live-island actions still compile
 * to the BML client runtime bundle served from `clientDir`.
 */
fun main(args: Array<String>) {
    val development = devMode()
    buildServer(args, System.getProperty("bml.clientDir"), graphqlEndpoint(), development).start()
}

internal fun graphqlEndpoint(): String =
    System.getenv("BML_GRAPHQL_ENDPOINT")?.ifBlank { null } ?: "http://localhost:8080/graphql"

internal fun resolvePort(args: Array<String>): Int = args.firstOrNull()?.toIntOrNull() ?: 9094

/**
 * Dev mode (`-Dbml.dev=true`, set by `./gradlew :notifications-web:run`): live reload, and every
 * CSS/JS tier served `no-store` so edits land on the next refresh. Deployed servers leave it off.
 */
internal fun devMode(): Boolean =
    (System.getProperty("bml.dev") ?: System.getenv("BML_DEV"))?.equals("true", ignoreCase = true) == true

internal fun resolveClientDir(
    path: String?,
    development: Boolean,
    projectDirectory: File = File("."),
): File? =
    (path?.let(::File)
        ?: projectDirectory.resolve(if (development) "build/generated/bml/js" else "build/generated/bml/js-prod"))
        .takeIf { it.isDirectory }

/**
 * The every-page theme CSS (the app.css tier) — src/main/client/notifications.css plus the
 * deployment's validated brand-color overrides. The base remains a real .css file so editors
 * and the BML IntelliJ plugin resolve its custom properties (`var(--ink)` etc.).
 */
internal fun notificationsCss(branding: Branding = branding()): String {
    val base = requireNotNull(object {}.javaClass.getResourceAsStream("/notifications.css")) {
        "notifications.css resource missing"
    }.readAllBytes().toString(Charsets.UTF_8)
    return """
        $base

        :root {
          --primary: ${branding.primaryColor};
          --accent: ${branding.accentColor};
        }
    """.trimIndent()
}

internal fun buildServer(
    args: Array<String>,
    clientDirProp: String?,
    graphqlEndpoint: String?,
    development: Boolean = devMode(),
    branding: Branding = branding(),
): BmlServer =
    BmlServer(
        project = CompiledProject(name = "notifications-web", version = "0.0.1"),
        pages = bml.generated.BmlPages.all,
        components = bml.generated.BmlComponents.all,
        componentRenderers = bml.generated.BmlComponents.renderers,
        islandDispatchers = bml.generated.BmlIslands.dispatchers,
        componentIslandDispatchers = bml.generated.BmlIslands.componentDispatchers,
        port = resolvePort(args),
        dev = development,
        clientDir = resolveClientDir(clientDirProp, development),
        // Loaders flag missing entities (ctx.notFound); the server answers with this page + a 404.
        notFoundRoute = "/404",
        // A loader that throws (upstream failure) answers with this page + a 500 — pages carry no
        // inline failure branches.
        errorRoute = "/500",
        graphqlEndpoint = graphqlEndpoint,
        globalCss = notificationsCss(branding),
    )
