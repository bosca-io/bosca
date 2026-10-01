package bosca.bml.contract

/**
 * Generates the two sides of a `<contract>`:
 * - a typed **TypeScript** client islands import and call;
 * - a Kotlin **server dispatch** descriptor `bml-server` routes to.
 *
 * The transport is JSON over `POST /_bml/contract/<Name>/<method>` with a JSON
 * array of arguments. The contract implementation accesses data only via the
 * GraphQL client; decode/invoke/encode wiring lands with the server.
 */
object ContractCodeGenerator {

    fun generateTypeScript(decl: ContractDecl): String = buildString {
        appendLine("// Generated client for contract ${decl.name}. Do not edit.")
        appendLine("""import { bmlContractCall } from "@bosca/bml"""")
        appendLine()
        appendLine("export interface ${decl.name} {")
        for (f in decl.functions) {
            val params = f.params.joinToString(", ") { "${it.name}: ${tsType(it.type)}" }
            appendLine("  ${f.name}($params): Promise<${tsType(f.returnType)}>")
        }
        appendLine("}")
        appendLine()
        appendLine("export const ${decl.name}: ${decl.name} = {")
        for (f in decl.functions) {
            val names = f.params.joinToString(", ") { it.name }
            appendLine("""  ${f.name}: ($names) => bmlContractCall("${decl.name}", "${f.name}", [$names]),""")
        }
        appendLine("}")
    }

    fun generateServerDispatcher(decl: ContractDecl, packageName: String): String {
        val methods = decl.functions.joinToString(", ") { "\"${it.name}\"" }
        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import bosca.bml.annotations.BmlContract")
            appendLine("import kotlinx.serialization.json.decodeFromJsonElement")
            appendLine("import kotlinx.serialization.json.jsonArray")
            appendLine()
            appendLine("/**")
            appendLine(" * Contract ${decl.name}: the site implements this interface (data access via the")
            appendLine(" * caller's GraphQL client only) and wires `${decl.name}Dispatcher(impl)` into its BmlServer.")
            appendLine(" */")
            appendLine("public interface ${decl.name} {")
            for (f in decl.functions) {
                val params = (listOf("gql: bosca.bml.graphql.GraphQLClient") + f.params.map { "${it.name}: ${it.type}" })
                    .joinToString(", ")
                appendLine("    public suspend fun ${f.name}($params): ${f.returnType}")
            }
            appendLine("}")
            appendLine()
            appendLine("/** Decode-invoke-encode wiring for [${decl.name}] — routed at `/_bml/contract/${decl.name}/{method}`. */")
            appendLine("@BmlContract")
            appendLine("public class ${decl.name}Dispatcher(")
            appendLine("    private val impl: ${decl.name},")
            appendLine(") : bosca.bml.render.BmlContractDispatcher {")
            appendLine("""    override val name: String = "${decl.name}"""")
            appendLine("    override val methods: List<String> = listOf($methods)")
            appendLine()
            appendLine("    override suspend fun dispatch(gql: bosca.bml.graphql.GraphQLClient?, method: String, argsJson: String): String {")
            appendLine("""        requireNotNull(gql) { "contract ${decl.name} needs a GraphQL endpoint" }""")
            appendLine("        val args = json.parseToJsonElement(argsJson).jsonArray")
            appendLine("        return when (method) {")
            for (f in decl.functions) {
                val argDecodes = f.params.mapIndexed { i, p -> "json.decodeFromJsonElement<${p.type}>(args[$i])" }
                val call = "impl.${f.name}(${(listOf("gql") + argDecodes).joinToString(", ")})"
                if (f.returnType.trim() == "Unit") {
                    appendLine("""            "${f.name}" -> { $call; "null" }""")
                } else {
                    appendLine("""            "${f.name}" -> json.encodeToString(kotlinx.serialization.serializer<${f.returnType}>(), $call)""")
                }
            }
            appendLine("""            else -> throw IllegalArgumentException("unknown contract method: ${decl.name}." + method)""")
            appendLine("        }")
            appendLine("    }")
            appendLine()
            appendLine("    private companion object {")
            appendLine("        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }")
            appendLine("    }")
            appendLine("}")
        }
    }

    /** Map a Kotlin type expression to a TypeScript type. */
    fun tsType(kotlin: String): String {
        var t = kotlin.trim()
        val nullable = t.endsWith("?")
        if (nullable) t = t.dropLast(1).trim()
        val base = when {
            t == "String" || t == "UUID" || t == "Uuid" || t.endsWith(".Uuid") -> "string"
            t in setOf("Int", "Long", "Short", "Byte", "Double", "Float") -> "number"
            t == "Boolean" -> "boolean"
            t == "Unit" -> "void"
            t.startsWith("List<") || t.startsWith("Set<") || t.startsWith("Collection<") || t.startsWith("Array<") ->
                tsType(t.substringAfter('<').substringBeforeLast('>')) + "[]"
            t.startsWith("Map<") -> "Record<string, ${tsType(t.substringAfter('<').substringBeforeLast('>').substringAfter(',').trim())}>"
            else -> "unknown"
        }
        return if (nullable) "$base | null" else base
    }
}
