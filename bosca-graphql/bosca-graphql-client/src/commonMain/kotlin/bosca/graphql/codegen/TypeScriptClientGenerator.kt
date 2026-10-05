package bosca.graphql.codegen

import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.Field
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ListType
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.Selection
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.Type
import bosca.graphql.printer.GraphQLPrinter
import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.schema.underlyingTypeName

/** How a GraphQL custom scalar maps to TypeScript. [tsType] is emitted; if [importName]/[importFrom] are both set, that named import is added. */
data class TsScalarMapping(val tsType: String, val importName: String? = null, val importFrom: String? = null)

/** TypeScript emission options. [runtimeModule] is the module the `bosca` transport is imported from (default the `@bosca/bml` island runtime). */
data class TypeScriptOptions(val runtimeModule: String = "@bosca/bml")

/**
 * Generates idiomatic, typed **TypeScript** for a GraphQL operation against a [GraphQLSchema] — a second emit
 * target alongside [KotlinClientGenerator] for BML islands. Emits per-operation `Variables` /
 * `Data` interfaces, the named types the operation references (enums as string-literal unions, input objects
 * as interfaces), and a typed `query`/`mutate` wrapper over the existing `@bosca/bml` `bosca` transport — so
 * island call sites are typed end-to-end (no `unknown`). No Apollo, no graphql-codegen.
 *
 * Coverage mirrors the Kotlin generator: scalars / enums / nested objects / lists / nullability; input objects
 * (recursive) + variables; configurable custom scalars; field **aliases**; **fragments** (named + non-narrowing
 * inline) flattened into the consuming type; and unions / interfaces → TypeScript **discriminated unions** on
 * `__typename` (one object member per concrete type, shared interface fields repeated in each, `tsc` narrows on
 * the literal — no runtime serializer needed). `__typename` is auto-added to polymorphic selections in the
 * emitted document. Follow-ons (fail fast): custom-scalar element serializers don't apply to TS (JSON-native).
 */
class TypeScriptClientGenerator(
    private val schema: GraphQLSchema,
    scalarMappings: Map<String, TsScalarMapping> = emptyMap(),
) {
    private val scalars: Map<String, TsScalarMapping> = BUILT_IN_SCALARS + scalarMappings

    // Reset at the start of each generate() pass.
    private val referencedEnums = linkedSetOf<String>()
    private val referencedInputs = linkedSetOf<String>()
    private val scalarImports = linkedMapOf<String, MutableSet<String>>() // module -> imported names
    private val declarations = mutableListOf<String>() // output object interfaces + union aliases, in emit order
    private var fragments: Map<String, FragmentDefinition> = emptyMap()

    fun generate(document: Document, options: TypeScriptOptions = TypeScriptOptions()): String {
        referencedEnums.clear()
        referencedInputs.clear()
        scalarImports.clear()
        declarations.clear()
        fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }

        val operation = document.definitions.filterIsInstance<OperationDefinition>().singleOrNull()
            ?: error("The generator supports exactly one operation per document.")
        require(operation.operation != OperationType.SUBSCRIPTION) {
            "TypeScript emission supports query and mutation operations; subscriptions are not supported yet."
        }
        val operationName = operation.name ?: error("The generator requires a named operation.")
        // An object type always has a name; the elvis (covered both ways by query + mutation-without-a-mutation-root)
        // guards a schema that defines no root for this operation. One line so the line stays covered.
        val rootType = (schema.rootType(operation.operation) ?: error("Schema defines no ${operation.operation.name.lowercase()} root type.")).name

        val dataName = "${operationName}Data"
        buildShape(dataName, rootType, operation.selectionSet) // fills `declarations` (Data + nested + unions)

        val hasVariables = operation.variableDefinitions.isNotEmpty()
        val variablesInterface = if (hasVariables) buildVariables(operationName, operation) else null
        val inputInterfaces = buildReferencedInputObjects()
        val enums = referencedEnums.map { buildEnum(it) }
        val transport = if (operation.operation == OperationType.QUERY) "query" else "mutate"

        return buildString {
            appendLine("// Generated GraphQL operation $operationName. Do not edit.")
            appendLine("""import { bosca } from "${options.runtimeModule}"""")
            scalarImports.forEach { (module, names) ->
                appendLine("""import { ${names.sorted().joinToString(", ")} } from "$module"""")
            }
            appendLine()
            enums.forEach { appendLine(it); appendLine() }
            inputInterfaces.forEach { appendLine(it); appendLine() }
            variablesInterface?.let { appendLine(it); appendLine() }
            declarations.forEach { appendLine(it); appendLine() }
            appendLine("export const $operationName = {")
            appendLine("""  operationName: "$operationName" as const,""")
            appendLine("  query: ${tsString(printedDocument(operation))},")
            appendLine("}")
            appendLine()
            if (hasVariables) {
                // Pass BOTH type args: TS disables inference for unspecified args once any is given, so V must be
                // explicit or typed variables would fall back to the transport's default Record<string, unknown>.
                appendLine("export function ${camelCase(operationName)}(variables: ${operationName}Variables): Promise<$dataName> {")
                appendLine("  return bosca.$transport<$dataName, ${operationName}Variables>({ query: $operationName.query, operationName: $operationName.operationName, variables })")
                appendLine("}")
            } else {
                appendLine("export function ${camelCase(operationName)}(): Promise<$dataName> {")
                appendLine("  return bosca.$transport<$dataName>({ query: $operationName.query, operationName: $operationName.operationName })")
                appendLine("}")
            }
        }
    }

    // ---- output shapes (object interfaces + discriminated-union aliases) ----

    /** Build the type for a selection on [graphqlType]: an object interface, or a discriminated-union alias if polymorphic. Returns the type name to reference. */
    private fun buildShape(name: String, graphqlType: String, selectionSet: SelectionSet): String {
        val analysis = analyzeSelection(graphqlType, selectionSet)
        return if (analysis.branches.isEmpty()) {
            buildObjectInterface(name, graphqlType, SelectionSet(analysis.commonSelections), discriminator = null)
        } else {
            buildUnionAlias(name, graphqlType, analysis)
        }
    }

    private fun buildObjectInterface(name: String, graphqlType: String, selectionSet: SelectionSet, discriminator: String?): String {
        val fields = flattenFields(graphqlType, selectionSet).filter { it.name != "__typename" }
        val lines = mutableListOf<String>()
        discriminator?.let { lines += """  __typename: "$it"""" }
        for (field in fields) {
            val responseName = field.alias ?: field.name
            val fieldDef = schema.field(graphqlType, field.name)
                ?: error("Field '${field.name}' does not exist on type '$graphqlType'.")
            val base = if (field.selectionSet != null) {
                buildShape(name + pascalCase(responseName), underlyingTypeName(fieldDef.type), field.selectionSet!!)
            } else {
                leafType(underlyingTypeName(fieldDef.type))
            }
            // Output fields are always present (a value or null), so never optional — nullability is `| null`.
            lines += "  $responseName: ${tsTypeRef(fieldDef.type, base)}"
        }
        declarations += "export interface $name {\n${lines.joinToString("\n")}\n}"
        return name
    }

    private fun buildUnionAlias(name: String, abstractType: String, analysis: Analysis): String {
        val members = analysis.branches.map { (concreteType, branchSelections) ->
            val combined = SelectionSet(analysis.commonSelections + branchSelections)
            buildObjectInterface(name + pascalCase(concreteType), concreteType, combined, discriminator = concreteType)
        }
        declarations += "export type $name =\n" + members.joinToString("\n") { "  | $it" }
        return name
    }

    // ---- variables + input objects ----

    private fun buildVariables(operationName: String, operation: OperationDefinition): String {
        val lines = operation.variableDefinitions.map { v ->
            val base = inputLeafType(underlyingTypeName(v.type))
            // A nullable variable is optional in TypeScript (matches the Kotlin `= null` default).
            val optional = if (v.type !is NonNullType) "?" else ""
            "  ${v.variable.name}$optional: ${tsTypeRef(v.type, base)}"
        }
        return "export interface ${operationName}Variables {\n${lines.joinToString("\n")}\n}"
    }

    private fun buildReferencedInputObjects(): List<String> {
        val emitted = mutableListOf<String>()
        val processed = mutableSetOf<String>()
        while (true) {
            val next = referencedInputs.firstOrNull { it !in processed } ?: break
            processed += next
            emitted += buildInputObject(next)
        }
        return emitted
    }

    private fun buildInputObject(name: String): String {
        // `name` was collected by inputLeafType, which only records actual input objects, so the cast always holds.
        val def = schema.type(name) as InputObjectTypeDefinition
        val lines = def.fields.map { f ->
            val base = inputLeafType(underlyingTypeName(f.type))
            val optional = if (f.type !is NonNullType) "?" else ""
            "  ${f.name}$optional: ${tsTypeRef(f.type, base)}"
        }
        return "export interface $name {\n${lines.joinToString("\n")}\n}"
    }

    private fun buildEnum(name: String): String {
        // `name` was collected by leafType/inputLeafType, which only record actual enums, so the cast always holds.
        val def = schema.type(name) as EnumTypeDefinition
        return "export type $name = ${def.values.joinToString(" | ") { "\"${it.name}\"" }}"
    }

    // ---- selection analysis (shared shape with the Kotlin generator) ----

    private data class Analysis(val commonSelections: List<Selection>, val branches: Map<String, List<Selection>>)

    private fun analyzeSelection(graphqlType: String, selectionSet: SelectionSet): Analysis {
        val common = mutableListOf<Selection>()
        val branches = linkedMapOf<String, MutableList<Selection>>()
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> common += selection
                is FragmentSpread -> {
                    val fragment = fragments[selection.name] ?: error("Unknown fragment '...${selection.name}'.")
                    if (fragmentApplies(graphqlType, fragment.typeCondition.name)) {
                        common += selection
                    } else {
                        branches.getOrPut(fragment.typeCondition.name) { mutableListOf() } += fragment.selectionSet.selections
                    }
                }
                is InlineFragment -> {
                    if (fragmentApplies(graphqlType, selection.typeCondition?.name)) {
                        common += selection
                    } else {
                        branches.getOrPut(selection.typeCondition!!.name) { mutableListOf() } += selection.selectionSet.selections
                    }
                }
            }
        }
        return Analysis(common, branches)
    }

    private fun flattenFields(graphqlType: String, selectionSet: SelectionSet): List<Field> {
        val byResponseName = linkedMapOf<String, Field>()

        fun add(selection: Selection) {
            when (selection) {
                is Field -> {
                    val responseName = selection.alias ?: selection.name
                    if (responseName !in byResponseName) byResponseName[responseName] = selection
                }
                is FragmentSpread -> {
                    val fragment = fragments[selection.name] ?: error("Unknown fragment '...${selection.name}'.")
                    require(fragmentApplies(graphqlType, fragment.typeCondition.name)) {
                        "Fragment '...${selection.name}' narrows '$graphqlType' — handled as a union."
                    }
                    fragment.selectionSet.selections.forEach { add(it) }
                }
                is InlineFragment -> {
                    val condition = selection.typeCondition?.name // computed once: null (always applies) or a narrowing type
                    require(fragmentApplies(graphqlType, condition)) {
                        "Inline fragment on '$condition' narrows '$graphqlType' — handled as a union."
                    }
                    selection.selectionSet.selections.forEach { add(it) }
                }
            }
        }

        selectionSet.selections.forEach { add(it) }
        return byResponseName.values.toList()
    }

    private fun fragmentApplies(currentType: String, condition: String?): Boolean {
        if (condition == null || condition == currentType) return true
        val interfaces = when (val t = schema.type(currentType)) {
            is ObjectTypeDefinition -> t.interfaces
            is InterfaceTypeDefinition -> t.interfaces
            else -> emptyList()
        }
        return interfaces.any { it.name == condition }
    }

    // ---- leaf type resolution ----

    private fun leafType(graphqlType: String): String = when {
        graphqlType in scalars -> scalarLeaf(graphqlType)
        schema.type(graphqlType) is EnumTypeDefinition -> {
            referencedEnums += graphqlType
            graphqlType
        }
        else -> error("Leaf type '$graphqlType' is not a built-in scalar, has no custom-scalar mapping, and is not an enum.")
    }

    private fun inputLeafType(graphqlType: String): String = when {
        graphqlType in scalars -> scalarLeaf(graphqlType)
        schema.type(graphqlType) is EnumTypeDefinition -> {
            referencedEnums += graphqlType
            graphqlType
        }
        schema.type(graphqlType) is InputObjectTypeDefinition -> {
            referencedInputs += graphqlType
            graphqlType
        }
        else -> error("Input type '$graphqlType' is not a built-in scalar, has no custom-scalar mapping, and is not an enum or input object.")
    }

    private fun scalarLeaf(name: String): String {
        val mapping = scalars.getValue(name)
        if (mapping.importName != null && mapping.importFrom != null) {
            scalarImports.getOrPut(mapping.importFrom) { mutableSetOf() } += mapping.importName
        }
        return mapping.tsType
    }

    // ---- type-reference rendering (nullability + lists) ----

    private fun tsTypeRef(type: Type, base: String): String = when (type) {
        is NonNullType -> renderNonNull(type.type, base)
        is ListType -> "Array<${tsTypeRef(type.type, base)}> | null"
        is NamedType -> "$base | null"
    }

    private fun renderNonNull(type: Type, base: String): String = when (type) {
        is ListType -> "Array<${tsTypeRef(type.type, base)}>"
        else -> base // a named type under non-null; GraphQL has no non-null-of-non-null, so nothing else reaches here
    }

    // Operation/type/field names are never empty, so index directly — avoids replaceFirstChar's dead empty-string arm.
    private fun pascalCase(name: String): String = name[0].uppercaseChar() + name.substring(1)

    private fun camelCase(name: String): String = name[0].lowercaseChar() + name.substring(1)

    /** The single-line GraphQL document to send: this operation + its fragments, with `__typename` injected into polymorphic selections, printed by the foundation [GraphQLPrinter]. */
    private fun printedDocument(operation: OperationDefinition): String =
        GraphQLPrinter.printCompact(Document(listOf(operation) + fragments.values).withInjectedTypenames())

    /** A TypeScript double-quoted string literal (no `$` escaping needed — double quotes don't interpolate). */
    private fun tsString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** The five spec built-in scalars mapped to TypeScript. Custom scalars are added via the constructor. */
        private val BUILT_IN_SCALARS: Map<String, TsScalarMapping> = mapOf(
            "Int" to TsScalarMapping("number"),
            "Float" to TsScalarMapping("number"),
            "String" to TsScalarMapping("string"),
            "Boolean" to TsScalarMapping("boolean"),
            "ID" to TsScalarMapping("string"),
        )
    }
}
