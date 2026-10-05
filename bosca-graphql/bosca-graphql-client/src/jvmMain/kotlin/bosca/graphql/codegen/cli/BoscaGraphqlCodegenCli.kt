package bosca.graphql.codegen.cli

import bosca.graphql.codegen.ScalarMapping
import bosca.graphql.codegen.TsScalarMapping
import java.io.File

/**
 * The entry point the `io.bosca.graphql` Gradle plugin runs (via JavaExec) for both codegen and schema refresh.
 * Kept off Gradle's own classpath (resolved by Maven coordinate) so the generator's kotlinx-serialization
 * dependency never clashes with the build's.
 *
 * ```
 * generate        --target kotlin     --schema <file> --queries <dir> --out <dir> --package <pkg> [--scalar Name:KotlinType[:import][:serializer]]…
 * generate        --target typescript --schema <file> --queries <dir> --out <dir> [--runtime-module @bosca/bml] [--scalar Name:tsType[:importName:importFrom]]…
 * download-schema --endpoint <url> --out <file> [--header "Name: Value"]…
 * ```
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "generate" -> runGenerate(parseOptions(args.drop(1)))
        "download-schema" -> runDownload(parseOptions(args.drop(1)))
        else -> error("Usage: <generate|download-schema> [options]; got '${args.joinToString(" ").ifEmpty { "<none>" }}'.")
    }
}

private fun runGenerate(options: Options) {
    when (val target = options.optional("target") ?: "kotlin") {
        "kotlin" -> runGenerateKotlin(options)
        "typescript", "ts" -> runGenerateTypeScript(options)
        else -> error("Unknown --target '$target'; expected 'kotlin' or 'typescript'.")
    }
}

private fun runGenerateKotlin(options: Options) {
    val outDir = options.required("out")
    val files = CodegenRunner.generate(
        schemaFile = File(options.required("schema")),
        queriesDir = File(options.required("queries")),
        outputDir = File(outDir),
        packageName = options.required("package"),
        scalarMappings = parseScalars(options.all("scalar")),
    )
    println("Generated ${files.size} Kotlin GraphQL client file(s) into $outDir")
}

private fun runGenerateTypeScript(options: Options) {
    val outDir = options.required("out")
    val files = CodegenRunner.generateTypeScript(
        schemaFile = File(options.required("schema")),
        queriesDir = File(options.required("queries")),
        outputDir = File(outDir),
        scalarMappings = parseTsScalars(options.all("scalar")),
        runtimeModule = options.optional("runtime-module") ?: "@bosca/bml",
    )
    println("Generated ${files.size} TypeScript GraphQL client file(s) into $outDir")
}

private fun runDownload(options: Options) {
    val sdl = SchemaDownloader.download(
        endpoint = options.required("endpoint"),
        headers = parseHeaders(options.all("header")),
    )
    val out = File(options.required("out")).apply { parentFile?.mkdirs() }
    out.writeText(sdl)
    println("Wrote schema SDL to ${out.absolutePath}")
}

/** Parsed `--name value` options; repeating a flag accumulates values. */
internal class Options(private val values: Map<String, List<String>>) {
    fun required(name: String): String = values[name]?.firstOrNull() ?: error("Missing required --$name option.")
    fun optional(name: String): String? = values[name]?.firstOrNull()
    fun all(name: String): List<String> = values[name].orEmpty()
}

internal fun parseOptions(args: List<String>): Options {
    val values = linkedMapOf<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        val token = args[i]
        require(token.startsWith("--")) { "Expected an option starting with '--', got '$token'." }
        val name = token.removePrefix("--")
        require(i + 1 < args.size) { "Option --$name requires a value." }
        values.getOrPut(name) { mutableListOf() } += args[i + 1]
        i += 2
    }
    return Options(values)
}

/** `GraphQLName:KotlinType[:import][:serializer]` — FQNs use dots, so ':' is a safe delimiter. */
internal fun parseScalars(specs: List<String>): Map<String, ScalarMapping> =
    specs.associate { spec ->
        val parts = spec.split(":")
        require(parts.size >= 2) { "Invalid --scalar '$spec'; expected GraphQLName:KotlinType[:import][:serializer]." }
        parts[0] to ScalarMapping(
            kotlinType = parts[1],
            imports = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { setOf(it) }.orEmpty(),
            serializerWith = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
        )
    }

/** `GraphQLName:tsType[:importName:importFrom]` for the TypeScript target — `:` is a safe delimiter (module specifiers use `@`/`/`). */
internal fun parseTsScalars(specs: List<String>): Map<String, TsScalarMapping> =
    specs.associate { spec ->
        val parts = spec.split(":")
        require(parts.size == 2 || parts.size == 4) {
            "Invalid --scalar '$spec' for TypeScript; expected GraphQLName:tsType or GraphQLName:tsType:importName:importFrom."
        }
        parts[0] to TsScalarMapping(parts[1], parts.getOrNull(2), parts.getOrNull(3))
    }

/** `Name: Value` — split on the first ':' so header values may themselves contain colons. */
internal fun parseHeaders(specs: List<String>): Map<String, String> =
    specs.associate { spec ->
        val idx = spec.indexOf(':')
        require(idx > 0) { "Invalid --header '$spec'; expected 'Name: Value'." }
        spec.substring(0, idx).trim() to spec.substring(idx + 1).trim()
    }
