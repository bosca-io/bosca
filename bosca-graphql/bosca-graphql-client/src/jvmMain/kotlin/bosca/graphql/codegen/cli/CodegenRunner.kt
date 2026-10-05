package bosca.graphql.codegen.cli

import bosca.graphql.codegen.GraphQLCodegen
import bosca.graphql.codegen.ScalarMapping
import bosca.graphql.codegen.TsScalarMapping
import bosca.graphql.codegen.TypeScriptOptions
import java.io.File

/**
 * Reads a checked-in schema + a tree of `.graphql` operation files, runs [GraphQLCodegen], and writes one
 * Kotlin file per operation under `outputDir/<package path>`. This is the JVM file-I/O wrapper the Gradle
 * plugin's codegen task invokes; the codegen itself stays pure/multiplatform in [GraphQLCodegen].
 */
object CodegenRunner {

    private val OPERATION_EXTENSIONS = setOf("graphql", "gql")

    /** Generate the typed Kotlin client into `outputDir/<package path>`; returns the files written. */
    fun generate(
        schemaFile: File,
        queriesDir: File,
        outputDir: File,
        packageName: String,
        scalarMappings: Map<String, ScalarMapping> = emptyMap(),
    ): List<File> {
        val codegen = GraphQLCodegen(schemaText(schemaFile), scalarMappings)
        val generated = codegen.generate(readSources(queriesDir), packageName)
        prune(outputDir, "kt")
        val packageDir = File(outputDir, packageName.replace('.', '/')).apply { mkdirs() }
        return generated.map { file -> File(packageDir, file.name).apply { writeText(file.content) } }
    }

    /** Generate the typed TypeScript client (for BML islands) directly into [outputDir]; returns the files written. */
    fun generateTypeScript(
        schemaFile: File,
        queriesDir: File,
        outputDir: File,
        scalarMappings: Map<String, TsScalarMapping> = emptyMap(),
        runtimeModule: String = "@bosca/bml",
    ): List<File> {
        val codegen = GraphQLCodegen(schemaText(schemaFile))
        val generated = codegen.generateTypeScript(readSources(queriesDir), scalarMappings, TypeScriptOptions(runtimeModule))
        prune(outputDir, "ts")
        outputDir.mkdirs()
        return generated.map { file -> File(outputDir, file.name).apply { writeText(file.content) } }
    }

    /** Read the schema SDL, failing with a clear message (not a raw I/O error) if the file is missing. */
    private fun schemaText(schemaFile: File): String {
        require(schemaFile.isFile) { "GraphQL schema file not found: ${schemaFile.absolutePath}" }
        return schemaFile.readText()
    }

    private fun readSources(queriesDir: File): List<String> {
        require(queriesDir.isDirectory) { "GraphQL operations directory not found: ${queriesDir.absolutePath}" }
        val sources = queriesDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in OPERATION_EXTENSIONS }
            .sortedBy { it.invariantSeparatorsPath } // deterministic ordering across machines
            .map { it.readText() }
            .toList()
        require(sources.isNotEmpty()) { "No .graphql operation files found under ${queriesDir.absolutePath}." }
        return sources
    }

    /** The task owns [outputDir]; clear stale generated files so a removed/renamed operation doesn't linger. */
    private fun prune(outputDir: File, extension: String) {
        outputDir.walkTopDown().filter { it.isFile && it.extension == extension }.forEach { it.delete() }
    }
}
