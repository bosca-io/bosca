package bosca.bml.sample

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import java.io.File

/**
 * Runs the sample BML site on the dedicated SSR server (bml-server):
 *
 *     ./gradlew :bml:bml-sample:bmlDev     # -> http://localhost:9090/ with hot reload
 *
 * Pages come from the compiler-generated `bml.generated.BmlPages` registry (no hand-listing); the
 * bundled client JS is served from the `bmlBundleClient` output under `/_bml/js/`. Components come
 * from `bml.generated.BmlComponents`, which lets the server serve each component's scoped CSS as a
 * cached `/_bml/css/<tag>.css` chunk and link only those a page renders (the three-tier asset model:
 * global `app.css`/`app.js`, per-page inline `<style>`, per-component linked chunks). This is the
 * template any BML site follows: build a [CompiledProject], hand [BmlServer] the generated pages +
 * components (and, for data, a `graphqlEndpoint`), and `start()`.
 */
fun main(args: Array<String>) {
    buildServer(args, System.getProperty("bml.clientDir"), System.getenv("BML_GRAPHQL_ENDPOINT")).start()
}

/** Server port from the first arg, defaulting to 9090 when absent or non-numeric. */
internal fun resolvePort(args: Array<String>): Int = args.firstOrNull()?.toIntOrNull() ?: 9090

/**
 * The bundled-client dir to serve, or null when it doesn't exist (e.g. `bmlBundleClient` hasn't run — no
 * node); SSR still works, islands just won't have their JS until bundled.
 */
internal fun resolveClientDir(path: String?): File? =
    File(path ?: "build/generated/bml/js").takeIf { it.isDirectory }

/** Builds the configured sample [BmlServer] (without starting it) — extracted so it's unit-testable. */
internal fun buildServer(args: Array<String>, clientDirProp: String?, graphqlEndpoint: String?): BmlServer =
    BmlServer(
        project = CompiledProject(name = "bml-sample", version = "0.0.1"),
        pages = bml.generated.BmlPages.all,
        components = bml.generated.BmlComponents.all,
        componentRenderers = bml.generated.BmlComponents.renderers, // enables /_bml/render/<tag> sliver updates
        islandDispatchers = bml.generated.BmlIslands.dispatchers, // enables /_bml/action/<stateKey> live @click
        port = resolvePort(args),
        clientDir = resolveClientDir(clientDirProp),
        graphqlEndpoint = graphqlEndpoint, // optional; the sample needs no data
        // The global tier — every page links these. In a real project, load them from app.css / app.js.
        globalCss = "body { margin: 0; font-family: system-ui, sans-serif; line-height: 1.5; }",
        globalJs = "console.log('bml app.js loaded')",
    )
