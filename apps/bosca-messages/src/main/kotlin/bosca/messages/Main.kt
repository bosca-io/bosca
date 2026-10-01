package bosca.messages

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import java.io.File

/**
 * The local preview site: the index page lists every email variant, and each preview route
 * responds the compiled template's raw rendered document (append `?text` for the plain-text
 * alternative). The brand assets and fonts serve from `public/` at the site root.
 *
 *     ./gradlew run        # -> http://localhost:4567/
 */
fun main(args: Array<String>) {
    BmlServer(
        project = CompiledProject(name = "bosca-messages", version = "0.0.1"),
        pages = bml.generated.BmlPages.all,
        port = args.firstOrNull()?.toIntOrNull() ?: 4567,
        dev = System.getProperty("bml.dev") == "true",
        publicDir = File("public").takeIf { it.isDirectory },
    ).start()
}
