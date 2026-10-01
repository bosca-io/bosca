package bosca.docs.index

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * CLI tool that scans Kotlin source files and compiles them into a single
 * JSON file of [SourceDocument] entries for indexing.
 *
 * Usage: SourceDocsCompiler <outputFile> <sourceDir1> [sourceDir2] ...
 */
object SourceDocsCompiler {

    private val json = Json { prettyPrint = true }
    private val parser = KotlinSourceParser()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("Usage: SourceDocsCompiler <outputFile> <sourceDir1> [sourceDir2] ...")
            System.exit(1)
        }

        val outputFile = File(args[0])
        val sourceDirs = args.drop(1).map { File(it) }

        val allDocuments = mutableListOf<SourceDocument>()

        for (sourceDir in sourceDirs) {
            if (!sourceDir.exists()) {
                System.err.println("Source directory does not exist: $sourceDir")
                continue
            }

            sourceDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.path.contains("src/main/kotlin") }
                .filter { !it.path.contains("/build/") }
                .forEach { file ->
                    val relativePath = file.relativeTo(sourceDir.parentFile.parentFile).path
                    val source = file.readText()
                    allDocuments.addAll(parser.parseAll(relativePath, source))
                }
        }

        val documents = parser.resolveTransitiveDeps(allDocuments)

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(json.encodeToString(documents))

        println("Compiled ${documents.size} source documents (from ${allDocuments.size} total) to ${outputFile.path}")
    }
}
