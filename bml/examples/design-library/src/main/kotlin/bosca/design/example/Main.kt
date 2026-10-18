package bosca.design.example

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.design.example.generated.BmlComponents
import bosca.design.example.generated.BmlIslands
import bosca.design.example.generated.BmlPages
import java.io.File

/** Runs the isolated design-library consumer without Git services or application bindings. */
fun main(args: Array<String>) {
    val css = checkNotNull(BmlPages::class.java.getResourceAsStream("/design-example.css"))
        .bufferedReader().use { it.readText() }
    BmlServer(
        project = CompiledProject(name = "design-library-example", version = "1"),
        pages = BmlPages.all,
        components = BmlComponents.all,
        componentRenderers = BmlComponents.renderers,
        islandDispatchers = BmlIslands.dispatchers,
        componentIslandDispatchers = BmlIslands.componentDispatchers,
        installationEndpoint = null,
        port = args.firstOrNull()?.toIntOrNull() ?: 9092,
        clientDir = System.getProperty("bml.clientDir")?.let(::File),
        globalCss = css,
    ).start()
}
