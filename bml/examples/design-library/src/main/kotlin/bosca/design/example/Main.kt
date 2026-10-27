package bosca.design.example

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import bosca.design.example.generated.BmlComponents
import bosca.design.example.generated.BmlIslands
import bosca.design.example.generated.BmlPages
import java.io.File

/** Runs the isolated design-library consumer with its local form and choice fixtures. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--check-resources") {
        for (path in listOf("design-example.css", "logback.xml", "META-INF/licenses/bml-design/geist.txt",
            "META-INF/licenses/bml-design/lucide.txt", "META-INF/licenses/bml-design/source-view.txt",
            "META-INF/licenses/bml-design/json-editor.txt", "META-INF/licenses/bml-design/document-editor.txt",
            "META-INF/licenses/bml-design/flow-editor.txt", "META-INF/licenses/bml-design/chart.txt")) {
            val bytes = checkNotNull(BmlPages::class.java.getResourceAsStream("/$path")) { "Missing resource: $path" }
                .use { it.readBytes() }
            println("$path\t${bosca.bml.project.ContentDigest.sha256(bytes)}")
        }
        return
    }
    val css = checkNotNull(BmlPages::class.java.getResourceAsStream("/design-example.css"))
        .bufferedReader().use { it.readText() }
    BmlServer(
        project = CompiledProject(name = "design-library-example", version = "1"),
        pages = BmlPages.all,
        components = BmlComponents.all,
        componentRenderers = BmlComponents.renderers,
        islandDispatchers = BmlIslands.dispatchers,
        componentIslandDispatchers = BmlIslands.componentDispatchers,
        contracts = listOf(SelectChoiceSearch()),
        installationEndpoint = null,
        port = args.firstOrNull()?.toIntOrNull() ?: 9092,
        clientDir = System.getProperty("bml.clientDir")?.let(::File),
        globalCss = css,
    ).start()
}
