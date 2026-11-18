package bosca.design.catalog

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.design.catalog.generated.BmlComponents
import bosca.design.catalog.generated.BmlIslands
import bosca.design.catalog.generated.BmlPages

/** Runs the independent component catalog with local demonstration state. */
fun main(args: Array<String>) {
    val css = requireNotNull(BmlPages::class.java.getResourceAsStream("/catalog.css"))
        .bufferedReader().use { it.readText() }
    BmlServer(
        project = CompiledProject(name = "bosca-design-catalog", version = "1"),
        pages = BmlPages.all,
        components = BmlComponents.all,
        componentRenderers = BmlComponents.renderers,
        islandDispatchers = BmlIslands.dispatchers,
        componentIslandDispatchers = BmlIslands.componentDispatchers,
        contracts = listOf(CatalogChoiceSearch()),
        installationEndpoint = null,
        port = args.firstOrNull()?.toIntOrNull() ?: 9093,
        globalCss = css,
    ).start()
}
