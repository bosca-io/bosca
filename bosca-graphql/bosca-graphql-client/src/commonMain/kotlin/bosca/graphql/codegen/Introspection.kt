package bosca.graphql.codegen

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a server's introspection result into the SDL our own parser ([bosca.graphql.schema.GraphQLSchema.fromSdl])
 * reads back. This is what the Gradle/CLI "refresh schema" task uses: run [QUERY] against the endpoint, feed the
 * JSON to [toSdl], and write the result as the checked-in `schema.graphqls` — so subsequent builds generate the
 * typed client entirely offline against that file.
 *
 * Pure (JSON → String) and multiplatform; the HTTP fetch lives in the JVM tooling. Emits only constructs the
 * Bosca parser accepts — built-in scalars and the `__*` introspection types are dropped (the schema builder
 * re-injects the built-ins), and descriptions/directive definitions are omitted (not needed for client codegen).
 */
object Introspection {

    /** The standard GraphQL introspection query (descriptions omitted — they're not used by codegen). */
    val QUERY: String = """
        query IntrospectionQuery {
          __schema {
            queryType { name }
            mutationType { name }
            subscriptionType { name }
            types { ...FullType }
          }
        }
        fragment FullType on __Type {
          kind
          name
          specifiedByURL
          isOneOf
          fields(includeDeprecated: true) { name args { ...InputValue } type { ...TypeRef } }
          inputFields { ...InputValue }
          interfaces { ...TypeRef }
          enumValues(includeDeprecated: true) { name }
          possibleTypes { ...TypeRef }
        }
        fragment InputValue on __InputValue { name type { ...TypeRef } defaultValue }
        fragment TypeRef on __Type {
          kind name
          ofType { kind name ofType { kind name ofType { kind name
            ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } }
        }
    """.trimIndent()

    private val BUILT_IN_SCALARS = setOf("Int", "Float", "String", "Boolean", "ID")

    /** Convert an introspection [result] (the full `{data:{__schema}}` response, a `{__schema}`, or a bare `__schema`) to SDL. */
    fun toSdl(result: JsonElement): String {
        val root = result.jsonObject
        val data = root["data"]
        val schemaElement = when {
            data is JsonObject && data["__schema"] != null -> data["__schema"]
            root["__schema"] != null -> root["__schema"]
            "types" in root -> root
            else -> null
        }
        val schema = (schemaElement as? JsonObject)
            ?: error("Introspection response has no __schema (an error response, perhaps?): $result")

        // Dispatch by kind through a map rather than a `when`/if-chain over String: both of those lower to a
        // hashCode lookupswitch whose per-case equals-false arms are dead (unreachable without a hash collision).
        // A map lookup collapses that to a single coverable null-check (known kind vs unrecognized kind).
        val renderers: Map<String, StringBuilder.(JsonObject, String) -> Unit> = mapOf(
            "SCALAR" to { obj, name -> if (name !in BUILT_IN_SCALARS) appendScalar(obj, name) },
            "ENUM" to { obj, name -> appendEnum(obj, name) },
            "OBJECT" to { obj, name -> appendFielded("type", obj, name) },
            "INTERFACE" to { obj, name -> appendFielded("interface", obj, name) },
            "UNION" to { obj, name -> appendUnion(obj, name) },
            "INPUT_OBJECT" to { obj, name -> appendInputObject(obj, name) },
        )
        return buildString {
            appendSchemaBlock(schema)
            for (type in schema["types"]?.jsonArray.orEmpty()) {
                val obj = type.jsonObject
                val name = obj.str("name") ?: continue
                if (name.startsWith("__")) continue // introspection meta-types
                renderers[obj.str("kind")]?.invoke(this, obj, name) // unrecognized kinds are skipped
            }
        }.trimEnd() + "\n"
    }

    private fun StringBuilder.appendSchemaBlock(schema: JsonObject) {
        val roots = mutableListOf<Pair<String, String>>()
        for (op in listOf("query", "mutation", "subscription")) {
            val rootType = schema["${op}Type"]
            if (rootType == null || rootType is JsonNull) continue
            val name = rootType.jsonObject.str("name") ?: continue
            roots += op to name
        }
        if (roots.isEmpty()) return
        appendLine("schema {")
        roots.forEach { (op, type) -> appendLine("  $op: $type") }
        appendLine("}").appendLine()
    }

    private fun StringBuilder.appendFielded(keyword: String, obj: JsonObject, name: String) {
        val interfaces = obj["interfaces"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name") }
        val implementsClause = if (interfaces.isEmpty()) "" else " implements " + interfaces.joinToString(" & ")
        appendLine("$keyword $name$implementsClause {")
        for (field in obj["fields"]?.jsonArray.orEmpty()) appendLine("  " + renderField(field.jsonObject))
        appendLine("}").appendLine()
    }

    private fun StringBuilder.appendScalar(obj: JsonObject, name: String) {
        val url = obj.str("specifiedByURL")
        val clause = if (url != null) " @specifiedBy(url: \"$url\")" else ""
        appendLine("scalar $name$clause").appendLine()
    }

    private fun StringBuilder.appendInputObject(obj: JsonObject, name: String) {
        val oneOf = if (obj.str("isOneOf") == "true") " @oneOf" else ""
        appendLine("input $name$oneOf {")
        for (field in obj["inputFields"]?.jsonArray.orEmpty()) appendLine("  " + renderInputValue(field.jsonObject))
        appendLine("}").appendLine()
    }

    private fun StringBuilder.appendEnum(obj: JsonObject, name: String) {
        appendLine("enum $name {")
        for (value in obj["enumValues"]?.jsonArray.orEmpty()) appendLine("  " + value.jsonObject.str("name"))
        appendLine("}").appendLine()
    }

    private fun StringBuilder.appendUnion(obj: JsonObject, name: String) {
        val members = obj["possibleTypes"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name") }
        appendLine("union $name = " + members.joinToString(" | ")).appendLine()
    }

    private fun renderField(field: JsonObject): String {
        val args = field["args"]?.jsonArray.orEmpty()
        val argsClause = if (args.isEmpty()) "" else "(" + args.joinToString(", ") { renderInputValue(it.jsonObject) } + ")"
        return "${field.str("name")}$argsClause: ${renderTypeRef(field["type"])}"
    }

    private fun renderInputValue(value: JsonObject): String {
        val defaultElement = value["defaultValue"]
        val defaultClause = if (defaultElement == null || defaultElement is JsonNull) "" else " = ${defaultElement.jsonPrimitive.content}"
        return "${value.str("name")}: ${renderTypeRef(value["type"])}$defaultClause"
    }

    private fun renderTypeRef(ref: JsonElement?): String {
        val obj = ref?.jsonObject ?: error("Introspection type reference is missing.")
        return when (obj.str("kind")) {
            "NON_NULL" -> renderTypeRef(obj["ofType"]) + "!"
            "LIST" -> "[" + renderTypeRef(obj["ofType"]) + "]"
            else -> obj.str("name") ?: error("Introspection named type reference is missing its name.")
        }
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key]
        return if (value == null || value is JsonNull) null else value.jsonPrimitive.content
    }
}
