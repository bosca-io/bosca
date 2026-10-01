package bosca.graphql.codegen

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumValue
import bosca.graphql.language.Field
import bosca.graphql.language.FloatValue
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.Selection
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.StringValue
import bosca.graphql.language.Type
import bosca.graphql.language.Value
import bosca.graphql.printer.GraphQLPrinter
import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.schema.underlyingTypeName

/**
 * How a GraphQL custom scalar maps to Kotlin. [kotlinType] is the type to emit (simple name or FQN);
 * [imports] are added to the file; [serializerWith] (a serializer FQN) — when set — annotates each
 * non-list property of this scalar with `@Serializable(with = …::class)`. A scalar mapped without a
 * [serializerWith] must already be serializable by kotlinx.serialization on its own.
 */
data class ScalarMapping(
    val kotlinType: String,
    val imports: Set<String> = emptySet(),
    val serializerWith: String? = null,
)

/**
 * A node in a named fragment's generated interface tree. The root is `I<Fragment>`; each inline
 * object field becomes a **nested** interface ([simpleName] = `<FieldPascal>`, [qualifiedName] =
 * `I<Fragment>.<FieldPascal>…`), mirroring the implementer data class's own nesting. Nesting (rather than a flat
 * `I<Fragment><Field>` name) keeps synthetic child names from colliding with same-spelled top-level fragment
 * interfaces (e.g. `IGuide.Template` vs. the `GuideTemplate` fragment's `IGuideTemplate`).
 *
 * [fieldNames] are the response names this node exposes (for matching `override`s on implementers); [declarations]
 * are the rendered `val x: T` interface-body lines, no indentation (object fields typed as their child interface's
 * in-scope name); [imports] are the custom-scalar imports the leaves need; [children] maps an object field's
 * response name to its nested node.
 *
 * A generated data class whose selection spreads this fragment implements [qualifiedName], `override`ing
 * [fieldNames]; object fields are covariant overrides whose concrete nested types implement the matching
 * [children] node's [qualifiedName].
 */
data class FragmentInterface(
    val simpleName: String,
    val qualifiedName: String,
    val fieldNames: List<String>,
    val declarations: List<String>,
    val imports: Set<String>,
    val children: Map<String, FragmentInterface>,
)

/**
 * Generates idiomatic, typed Kotlin for a GraphQL operation against a [GraphQLSchema] — the Bosca-native,
 * Apollo-free client codegen. Emits per-operation `@Serializable` `Data` / `Variables` data classes with
 * **explicit** serializers (no reflective `serializer<T>()` — GraalVM-native-safe), plus the named types the
 * operation references.
 *
 * Coverage:
 * - scalar / enum / nested-object selections; lists; nullability from the schema; enums
 *   (`@Serializable enum class`); input objects (recursive); variables; configurable custom-scalar mapping.
 * - field **aliases**; **fragments** (named spreads + non-narrowing/widening inline fragments)
 *   flattened into the consuming type, with the fragment definitions carried in the document.
 * - unions / interfaces → **sealed hierarchies**. A polymorphic selection (inline fragments or
 *   narrowing spreads onto concrete types) becomes a `sealed interface` with one `@Serializable data class`
 *   per concrete type, shared interface fields hoisted as `override val`s, and a forward-compatible `Other`
 *   branch for unknown `__typename`. Decoding dispatches on `__typename` via a generated, explicit
 *   [kotlinx.serialization.KSerializer] (content-based; no reflection). `__typename` is auto-added to
 *   polymorphic selections in the emitted document.
 *
 * Follow-ons (fail fast with a clear message): polymorphic or custom-scalar fields **inside lists**, and
 * object-typed common fields on a polymorphic selection. The document string comes from a minimal operation
 * printer; the foundation's full AST printer will subsume it.
 */
class KotlinClientGenerator(
    private val schema: GraphQLSchema,
    scalarMappings: Map<String, ScalarMapping> = emptyMap(),
) {
    private val scalars: Map<String, ScalarMapping> = BUILT_IN_SCALARS + scalarMappings

    // Reset at the start of each generate() pass.
    private val referencedEnums = linkedSetOf<String>()
    private val referencedInputs = linkedSetOf<String>()
    private val extraImports = linkedSetOf<String>()
    private val topLevelSerializers = mutableListOf<String>()

    // Named fragments SPREAD anywhere in the current pass — the orchestrator reads this after each call to emit
    // one I<Fragment> interface tree per referenced fragment (same pattern as referencedEnumNames()).
    private val referencedFragments = linkedSetOf<String>()

    // FQNs of custom-scalar serializers referenced in the current file; emitted as a `@file:UseSerializers(…)`
    // header so kotlinx applies them to the scalar type everywhere — including inside `List<…>` and nested types,
    // which a per-property `@Serializable(with = …)` annotation cannot express.
    private val fileSerializers = linkedSetOf<String>()
    private var fragments: Map<String, FragmentDefinition> = emptyMap()

    /**
     * Generate the Kotlin for one operation in [document], into [packageName]. [hoistedTypes] names enum/input
     * types emitted in their own (shared) files — they are referenced but NOT re-declared here, so the
     * orchestrator can de-duplicate types shared across operations (same-package references need no import).
     * The default empty set keeps single-operation output fully self-contained.
     */
    fun generate(document: Document, packageName: String, hoistedTypes: Set<String> = emptySet()): String {
        referencedEnums.clear()
        referencedInputs.clear()
        extraImports.clear()
        topLevelSerializers.clear()
        fileSerializers.clear()
        referencedFragments.clear()
        fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }

        val operation = document.definitions.filterIsInstance<OperationDefinition>().singleOrNull()
            ?: error("The generator supports exactly one operation per document.")
        // query / mutation / subscription all generate the same typed shape; the transport (request/response vs.
        // streaming) is the caller's concern, so the generator places no restriction on the operation type.
        val operationName = operation.name ?: error("The generator requires a named operation.")
        // A built schema always has a query root (SchemaBuilder enforces it) and an object type always has a name,
        // so the elvis is a guard against a malformed hand-built schema — kept, but on one line so the line is covered.
        val rootType = (schema.rootType(operation.operation) ?: error("Schema defines no ${operation.operation.name.lowercase()} root type.")).name

        val dataClassName = "${operationName}Data"
        val dataClass = buildSelectionType(dataClassName, dataClassName, rootType, operation.selectionSet, indent = 0).text
        val hasVariables = operation.variableDefinitions.isNotEmpty()
        val variablesType = if (hasVariables) "$operationName.Variables" else "Unit"
        val variablesClass = if (hasVariables) buildVariables(operation, indent = 1) else null

        val inputClasses = buildReferencedInputObjects(hoistedTypes)
        val enumClasses = referencedEnums.filter { it !in hoistedTypes }.map { buildEnum(it) }

        val imports = linkedSetOf(
            "bosca.graphql.client.GraphQLJson",
            "bosca.graphql.client.BoscaOperation",
            "kotlinx.serialization.Serializable",
            "kotlinx.serialization.json.JsonElement",
            "kotlinx.serialization.json.JsonObject",
            "kotlinx.serialization.json.decodeFromJsonElement",
        )
        if (hasVariables) {
            imports += "kotlinx.serialization.json.encodeToJsonElement"
            imports += "kotlinx.serialization.json.jsonObject"
        }
        imports += extraImports // custom-scalar + polymorphic-serializer imports

        return buildString {
            append(fileUseSerializersHeader())
            appendLine("package $packageName")
            appendLine()
            imports.sorted().forEach { appendLine("import $it") }
            appendLine()
            enumClasses.forEach { appendLine(it); appendLine() }
            inputClasses.forEach { appendLine(it); appendLine() }
            append(dataClass)
            appendLine()
            topLevelSerializers.forEach { appendLine(); appendLine(it) }
            appendLine()
            appendLine("object $operationName : BoscaOperation<$variablesType, $dataClassName> {")
            variablesClass?.let { appendLine(it); appendLine() }
            appendLine("    override val operationName: String = ${kotlinString(operationName)}")
            appendLine()
            appendLine("    override val document: String =")
            appendLine("        ${kotlinString(printedDocument(operation))}")
            appendLine()
            if (hasVariables) {
                appendLine("    override fun encodeVariables(variables: Variables): JsonObject =")
                appendLine("        GraphQLJson.encodeToJsonElement(Variables.serializer(), variables).jsonObject")
            } else {
                appendLine("    override fun encodeVariables(variables: Unit): JsonObject = JsonObject(emptyMap())")
            }
            appendLine()
            appendLine("    override fun decodeData(data: JsonElement): $dataClassName =")
            appendLine("        GraphQLJson.decodeFromJsonElement($dataClassName.serializer(), data)")
            append("}")
            appendLine()
        }
    }

    /** The result of generating a selection's type: the (nested) declaration [text] and, for polymorphic selections, the top-level [serializerName] to bind via `@Serializable(with = …)`. */
    private data class BuiltType(val text: String, val serializerName: String?)

    /**
     * Build the type for a selection on [graphqlType]: a plain data class, or a sealed hierarchy when the
     * selection is polymorphic. [qualified] is this type's full nested path (for serializer naming + references).
     * [implement] are fragment-interface nodes this data class must satisfy (cascaded from a parent whose
     * fragment selected this object field); the data class additionally implements any fragment it spreads here.
     */
    private fun buildSelectionType(
        className: String,
        qualified: String,
        graphqlType: String,
        selectionSet: SelectionSet,
        indent: Int,
        implement: List<FragmentInterface> = emptyList(),
    ): BuiltType {
        val analysis = analyzeSelection(graphqlType, selectionSet)
        return if (analysis.branches.isEmpty()) {
            val fields = flattenFields(graphqlType, SelectionSet(analysis.commonSelections))
            // Fragment interfaces this data class implements: those cascaded from the parent plus any named
            // fragment APPLYINGLY spread directly in this selection.
            val localSpreads = analysis.commonSelections.filterIsInstance<FragmentSpread>()
                .filter { fragmentApplies(graphqlType, fragments.getValue(it.name).typeCondition.name) }
                .map { referencedFragments += it.name; buildFragmentInterface(it.name) }
            // A fragment can be reached both as a cascaded parent interface and as a local spread; dedup so the
            // data class never lists the same supertype twice ("A supertype appears twice").
            val allImpl = (implement + localSpreads).distinctBy { it.qualifiedName }
            BuiltType(dataClassText(className, qualified, graphqlType, fields, indent, superType = null, overrides = emptySet(), implement = allImpl), null)
        } else {
            buildSealedType(className, qualified, graphqlType, analysis, indent)
        }
    }

    // ---- plain data class ----

    private fun dataClassText(
        className: String,
        qualified: String,
        graphqlType: String,
        fields: List<Field>,
        indent: Int,
        superType: String?,
        overrides: Set<String>,
        implement: List<FragmentInterface> = emptyList(),
    ): String {
        val pad = "    ".repeat(indent)
        val padField = "    ".repeat(indent + 1)
        val properties = mutableListOf<String>()
        val nested = mutableListOf<String>()
        // A field is overridden if it comes from the sealed parent ([overrides]) or from any implemented fragment.
        val fragmentFieldNames = implement.flatMapTo(linkedSetOf()) { it.fieldNames }

        for (field in fields) {
            if (field.name == "__typename") continue // discriminator / meta-field, not a data property
            val responseName = field.alias ?: field.name
            val fieldDef = schema.field(graphqlType, field.name)
                ?: error("Field '${field.name}' does not exist on type '$graphqlType'.")
            val modifier = if (responseName in overrides || responseName in fragmentFieldNames) "override " else ""

            if (field.selectionSet != null) {
                val nestedName = pascalCase(responseName)
                // Cascade: the nested type must implement each implemented fragment's matching child interface.
                val nestedImplement = implement.mapNotNull { it.children[responseName] }
                val built = buildSelectionType(nestedName, "$qualified.$nestedName", underlyingTypeName(fieldDef.type), field.selectionSet!!, indent + 1, nestedImplement)
                nested += built.text
                // No per-property serializer annotation: a nested object is `@Serializable` and a polymorphic type
                // carries `@Serializable(with = …)` on its own declaration, so kotlinx resolves it automatically —
                // including inside `List<…>` (which a per-property annotation cannot express).
                properties += "$padField$modifier" + "val $responseName: ${kotlinTypeRef(fieldDef.type, nestedName)}"
            } else {
                val leaf = outputLeafType(underlyingTypeName(fieldDef.type))
                properties += "$padField$modifier" + "val $responseName: ${kotlinTypeRef(fieldDef.type, leaf.kotlinType)}"
            }
        }

        val supertypes = (listOfNotNull(superType) + implement.map { it.qualifiedName }).distinct()
        val extends = if (supertypes.isEmpty()) "" else " : " + supertypes.joinToString(", ")
        return buildString {
            if (properties.isEmpty()) {
                appendLine("$pad@Serializable")
                append("${pad}object $className$extends")
                return@buildString
            }
            appendLine("$pad@Serializable")
            if (nested.isEmpty()) {
                appendLine("${pad}data class $className(")
                properties.forEach { appendLine("$it,") }
                append("$pad)$extends")
            } else {
                appendLine("${pad}data class $className(")
                properties.forEach { appendLine("$it,") }
                appendLine("$pad)$extends {")
                nested.forEachIndexed { i, n ->
                    appendLine(n)
                    if (i < nested.size - 1) appendLine()
                }
                append("$pad}")
            }
        }
    }

    // ---- sealed hierarchy for a polymorphic (interface / union) selection ----

    private fun buildSealedType(className: String, qualified: String, abstractType: String, analysis: Analysis, indent: Int): BuiltType {
        val pad = "    ".repeat(indent)
        val padMember = "    ".repeat(indent + 1)

        val commonFields = flattenFields(abstractType, SelectionSet(analysis.commonSelections)).filter { it.name != "__typename" }
        val overrides = commonFields.mapTo(linkedSetOf()) { it.alias ?: it.name }

        // Shared interface fields (leaves only — object-typed common fields are a follow-on).
        val commonVals = commonFields.map { field ->
            val fieldDef = schema.field(abstractType, field.name)
                ?: error("Field '${field.name}' does not exist on interface '$abstractType'.")
            require(field.selectionSet == null) {
                "Object-typed common field '${field.name}' on a polymorphic selection is not supported yet."
            }
            val leaf = outputLeafType(underlyingTypeName(fieldDef.type))
            "${padMember}val ${field.alias ?: field.name}: ${kotlinTypeRef(fieldDef.type, leaf.kotlinType)}"
        }

        val subtypes = analysis.branches.map { (concreteType, branchSelections) ->
            val combined = SelectionSet(analysis.commonSelections + branchSelections)
            val fields = flattenFields(concreteType, combined).filter { it.name != "__typename" }
            dataClassText(concreteType, "$qualified.$concreteType", concreteType, fields, indent + 1, superType = className, overrides = overrides)
        }
        val other = dataClassText("Other", "$qualified.Other", abstractType, commonFields, indent + 1, superType = className, overrides = overrides)

        val sealedText = buildString {
            appendLine("$pad@Serializable(with = ${serializerName(qualified)}::class)")
            appendLine("${pad}sealed interface $className {")
            commonVals.forEach { appendLine(it) }
            if (commonVals.isNotEmpty()) appendLine()
            (subtypes + other).forEachIndexed { i, t ->
                appendLine(t)
                if (i < subtypes.size) appendLine() // blank line between members
            }
            append("$pad}")
        }

        registerPolymorphicSerializer(qualified, analysis.branches.keys)
        return BuiltType(sealedText, serializerName(qualified))
    }

    /** A generated, explicit `KSerializer` that dispatches on `__typename` (content-based; native-safe). */
    private fun registerPolymorphicSerializer(qualified: String, concreteTypes: Set<String>) {
        extraImports += listOf(
            "kotlinx.serialization.KSerializer",
            "kotlinx.serialization.descriptors.SerialDescriptor",
            "kotlinx.serialization.descriptors.buildClassSerialDescriptor",
            "kotlinx.serialization.encoding.Decoder",
            "kotlinx.serialization.encoding.Encoder",
            "kotlinx.serialization.json.JsonDecoder",
            "kotlinx.serialization.json.jsonObject",
            "kotlinx.serialization.json.jsonPrimitive",
        )
        val name = serializerName(qualified)
        topLevelSerializers += buildString {
            appendLine("object $name : KSerializer<$qualified> {")
            appendLine("    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(${kotlinString(qualified)})")
            appendLine()
            appendLine("    override fun deserialize(decoder: Decoder): $qualified {")
            appendLine("        val element = (decoder as JsonDecoder).decodeJsonElement()")
            appendLine("        return when (element.jsonObject[\"__typename\"]?.jsonPrimitive?.content) {")
            concreteTypes.forEach { t ->
                appendLine("            ${kotlinString(t)} -> GraphQLJson.decodeFromJsonElement($qualified.$t.serializer(), element)")
            }
            appendLine("            else -> GraphQLJson.decodeFromJsonElement($qualified.Other.serializer(), element)")
            appendLine("        }")
            appendLine("    }")
            appendLine()
            appendLine("    override fun serialize(encoder: Encoder, value: $qualified): Unit =")
            appendLine("        error(\"GraphQL response type '$qualified' is not serializable\")")
            append("}")
        }
    }

    private fun serializerName(qualified: String): String = qualified.replace(".", "") + "Serializer"

    /** The `@file:UseSerializers(…)` header binding the file's custom-scalar serializers, or "" if none are used. */
    private fun fileUseSerializersHeader(): String =
        if (fileSerializers.isEmpty()) {
            ""
        } else {
            "@file:kotlinx.serialization.UseSerializers(" + fileSerializers.sorted().joinToString(", ") { "$it::class" } + ")\n\n"
        }

    // ---- selection analysis (common vs polymorphic branches) ----

    private data class Analysis(val commonSelections: List<Selection>, val branches: Map<String, List<Selection>>)

    private fun analyzeSelection(graphqlType: String, selectionSet: SelectionSet): Analysis {
        val common = mutableListOf<Selection>()
        val branches = linkedMapOf<String, MutableList<Selection>>()
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> common += selection
                is FragmentSpread -> {
                    val fragment = fragments[selection.name] ?: error("Unknown fragment '...${selection.name}'.")
                    val condition = fragment.typeCondition.name
                    if (fragmentApplies(graphqlType, condition)) {
                        common += selection
                    } else {
                        branches.getOrPut(condition) { mutableListOf() } += fragment.selectionSet.selections
                    }
                }
                is InlineFragment -> {
                    val condition = selection.typeCondition?.name
                    if (fragmentApplies(graphqlType, condition)) {
                        common += selection
                    } else {
                        branches.getOrPut(condition!!) { mutableListOf() } += selection.selectionSet.selections
                    }
                }
            }
        }
        return Analysis(common, branches)
    }

    /** Flatten a (non-polymorphic) selection set on [graphqlType], inlining same-type/widening fragments. */
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
                        "Fragment '...${selection.name}' narrows '$graphqlType' — polymorphic fragments need a sealed type."
                    }
                    fragment.selectionSet.selections.forEach { add(it) }
                }
                is InlineFragment -> {
                    val condition = selection.typeCondition?.name // computed once: null (always applies) or a narrowing type
                    require(fragmentApplies(graphqlType, condition)) {
                        "Inline fragment on '$condition' narrows '$graphqlType' — needs a sealed type."
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

    // ---- variables + input objects ----

    private fun buildVariables(operation: OperationDefinition, indent: Int): String {
        val pad = "    ".repeat(indent)
        val padField = "    ".repeat(indent + 1)
        val properties = operation.variableDefinitions.map { v ->
            val leaf = inputLeafType(underlyingTypeName(v.type))
            val default = defaultClause(v.type, v.defaultValue, leaf.kotlinType)
            "$padField" + "val ${v.variable.name}: ${kotlinTypeRef(v.type, leaf.kotlinType)}$default"
        }
        return buildString {
            appendLine("$pad@Serializable")
            appendLine("${pad}data class Variables(")
            properties.forEach { appendLine("$it,") }
            append("$pad)")
        }
    }

    private fun buildReferencedInputObjects(hoistedTypes: Set<String>): List<String> {
        val emitted = mutableListOf<String>()
        val processed = mutableSetOf<String>()
        while (true) {
            val next = referencedInputs.firstOrNull { it !in processed } ?: break
            processed += next
            val text = buildInputObject(next) // always build to expand the transitive closure...
            if (next !in hoistedTypes) emitted += text // ...but only emit non-hoisted ones inline
        }
        return emitted
    }

    /** The enum types referenced by the last [generate] call (output selections + input fields). */
    fun referencedEnumNames(): Set<String> = referencedEnums.toSet()

    /** The input object types (transitively) referenced by the last [generate] call. */
    fun referencedInputNames(): Set<String> = referencedInputs.toSet()

    /** The named fragments spread (and therefore implemented as `I<Fragment>` interfaces) by the last [generate] call. */
    fun referencedFragmentNames(): Set<String> = referencedFragments.toSet()

    /** Supply the full fragment pool used by [emitFragmentInterfaceFile] (a referenced fragment may live in any source). */
    fun useFragments(all: Map<String, FragmentDefinition>) {
        fragments = all
    }

    /** Build the recursive interface tree for the named fragment [fragmentName] (root `I<Fragment>` + nested object nodes). */
    private fun buildFragmentInterface(fragmentName: String): FragmentInterface {
        val fragment = fragments[fragmentName] ?: error("Unknown fragment '...$fragmentName'.")
        val root = INTERFACE_PREFIX + fragmentName
        return buildFragmentInterfaceNode(root, root, fragment.typeCondition.name, fragment.selectionSet)
    }

    /**
     * [simpleName] is this node's own declared interface name (`I<Fragment>` at the root, `<FieldPascal>` for a
     * nested object field); [qualifiedName] is its full reference path (e.g. `IGuide.Template`). Object-field
     * declarations reference the child by the name in scope: a nested interface by its [simpleName], a spread
     * fragment by its top-level `I<Fragment>`.
     */
    private fun buildFragmentInterfaceNode(simpleName: String, qualifiedName: String, graphqlType: String, selectionSet: SelectionSet): FragmentInterface {
        val children = linkedMapOf<String, FragmentInterface>()
        val fieldNames = mutableListOf<String>()
        val declarations = mutableListOf<String>()
        val imports = linkedSetOf<String>()
        for (field in flattenFields(graphqlType, selectionSet)) {
            if (field.name == "__typename") continue
            val responseName = field.alias ?: field.name
            val fieldDef = schema.field(graphqlType, field.name) ?: error("Field '${field.name}' does not exist on type '$graphqlType'.")
            val base = if (field.selectionSet != null) {
                val sub = field.selectionSet!!
                val childType = underlyingTypeName(fieldDef.type)
                // A polymorphic object field (union/interface selection) is not exposed on the fragment interface
                // (v1): implementers keep it as their own sealed type, just not unified through the interface.
                if (analyzeSelection(childType, sub).branches.isNotEmpty()) continue
                // If the field is exactly one fragment spread (`x { ...G }`, ignoring the auto-injected `__typename`),
                // reference that fragment's top-level interface directly — the implementer's nested type satisfies it
                // via its own spread, so no synthetic child interface and no cascade are needed. A lone spread here
                // necessarily applies: a narrowing spread would have become a branch and been skipped just above.
                val meaningful = sub.selections.filterNot { it is Field && it.name == "__typename" }
                val singleSpread = meaningful.singleOrNull() as? FragmentSpread
                if (singleSpread != null) {
                    INTERFACE_PREFIX + singleSpread.name
                } else {
                    val childSimple = pascalCase(responseName)
                    val child = buildFragmentInterfaceNode(childSimple, "$qualifiedName.$childSimple", childType, sub)
                    children[responseName] = child
                    childSimple
                }
            } else {
                val graphqlLeaf = underlyingTypeName(fieldDef.type)
                scalars[graphqlLeaf]?.imports?.let { imports += it }
                outputLeafType(graphqlLeaf).kotlinType
            }
            fieldNames += responseName
            declarations += "val $responseName: ${kotlinTypeRef(fieldDef.type, base)}"
        }
        return FragmentInterface(simpleName, qualifiedName, fieldNames, declarations, imports, children)
    }

    /**
     * Emit a standalone file for a named fragment's interface tree: `I<Fragment>` with a **nested** interface per
     * inline object field, recursively. Object fields are typed as their child interface (covariant with each
     * implementer's concrete nested type); leaves carry any custom-scalar imports. Nesting (vs. a flat
     * `I<Fragment><Field>` name) prevents synthetic child names from colliding with top-level fragment interfaces.
     */
    fun emitFragmentInterfaceFile(fragmentName: String, packageName: String): String {
        val root = buildFragmentInterface(fragmentName)
        val imports = collectImports(root).distinct().sorted()
        return buildString {
            appendLine("package $packageName")
            appendLine()
            imports.forEach { appendLine("import $it") }
            if (imports.isNotEmpty()) appendLine()
            append(renderFragmentInterface(root, indent = 0))
            appendLine()
        }
    }

    private fun collectImports(node: FragmentInterface): List<String> =
        node.imports.toList() + node.children.values.flatMap { collectImports(it) }

    private fun renderFragmentInterface(node: FragmentInterface, indent: Int): String {
        val pad = "    ".repeat(indent)
        val padBody = "    ".repeat(indent + 1)
        return buildString {
            appendLine("${pad}interface ${node.simpleName} {")
            node.declarations.forEach { appendLine("$padBody$it") }
            node.children.values.forEach { child ->
                appendLine()
                appendLine(renderFragmentInterface(child, indent + 1))
            }
            append("$pad}")
        }
    }

    /** Emit a standalone file for a shared enum — used by the orchestrator when hoisting types shared across operations. */
    fun emitEnumFile(name: String, packageName: String): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("import kotlinx.serialization.Serializable")
        appendLine()
        append(buildEnum(name))
        appendLine()
    }

    /** Emit a standalone file for a shared input object — used by the orchestrator when hoisting types shared across operations. */
    fun emitInputFile(name: String, packageName: String): String {
        extraImports.clear()
        fileSerializers.clear()
        val body = buildInputObject(name) // records any custom-scalar imports + serializers
        val imports = linkedSetOf("kotlinx.serialization.Serializable").apply { addAll(extraImports) }
        return buildString {
            append(fileUseSerializersHeader())
            appendLine("package $packageName")
            appendLine()
            imports.sorted().forEach { appendLine("import $it") }
            appendLine()
            append(body)
            appendLine()
        }
    }

    private fun buildInputObject(name: String): String {
        // `name` was collected by inputLeafType, which only records actual input objects, so the cast always holds.
        val def = schema.type(name) as InputObjectTypeDefinition
        val properties = def.fields.map { f ->
            val leaf = inputLeafType(underlyingTypeName(f.type))
            val default = defaultClause(f.type, f.defaultValue, leaf.kotlinType)
            "    " + "val ${f.name}: ${kotlinTypeRef(f.type, leaf.kotlinType)}$default"
        }
        return buildString {
            appendLine("@Serializable")
            appendLine("data class $name(")
            properties.forEach { appendLine("$it,") }
            append(")")
        }
    }

    /**
     * The Kotlin default for an input field / variable. An explicit GraphQL default (incl. on a non-null field, e.g.
     * `swimlaneStrategy: WorkOpsSwimlaneStrategy! = NONE`) is rendered as the equivalent Kotlin initializer so the
     * field stays omittable (matching GraphQL semantics); an absent default on a nullable type falls back to `= null`;
     * a non-null field with no default is required. With `encodeDefaults = true` the rendered default still serializes.
     */
    private fun defaultClause(type: Type, defaultValue: Value?, leafKotlinType: String): String = when {
        defaultValue != null -> " = ${renderDefaultValue(defaultValue, leafKotlinType)}"
        type !is NonNullType -> " = null"
        else -> ""
    }

    // [leafKotlinType] is the field's leaf Kotlin type — constant across list nesting, so list elements pass it
    // through unchanged (a nested list simply recurses on ListValue).
    private fun renderDefaultValue(value: Value, leafKotlinType: String): String = when (value) {
        is NullValue -> "null"
        is BooleanValue -> value.value.toString()
        is IntValue -> if (leafKotlinType.endsWith("Long")) "${value.value}L" else value.value // Long needs the `L` suffix
        is FloatValue -> value.value
        is StringValue -> kotlinString(value.value)
        is EnumValue -> "$leafKotlinType.${value.value}"
        is ListValue -> "listOf(${value.values.joinToString(", ") { renderDefaultValue(it, leafKotlinType) }})"
        // Object and (grammatically impossible) variable defaults are not rendered.
        else -> error("A default value of type ${value::class.simpleName} is not supported (GQLC).")
    }

    private fun buildEnum(name: String): String {
        // `name` was collected by leafType/inputLeafType, which only record actual enums, so the cast always holds.
        val def = schema.type(name) as EnumTypeDefinition
        return buildString {
            appendLine("@Serializable")
            appendLine("enum class $name {")
            def.values.forEach { appendLine("    ${it.name},") }
            append("}")
        }
    }

    // ---- leaf type resolution (records referenced enums/inputs + scalar imports) ----

    private data class LeafType(val kotlinType: String, val serializerWith: String?)

    private fun outputLeafType(graphqlType: String): LeafType = when {
        graphqlType in scalars -> scalarLeaf(graphqlType)
        schema.type(graphqlType) is EnumTypeDefinition -> {
            referencedEnums += graphqlType
            LeafType(graphqlType, null)
        }
        else -> error("Leaf type '$graphqlType' is not a built-in scalar, has no custom-scalar mapping, and is not an enum.")
    }

    private fun inputLeafType(graphqlType: String): LeafType = when {
        graphqlType in scalars -> scalarLeaf(graphqlType)
        schema.type(graphqlType) is EnumTypeDefinition -> {
            referencedEnums += graphqlType
            LeafType(graphqlType, null)
        }
        schema.type(graphqlType) is InputObjectTypeDefinition -> {
            referencedInputs += graphqlType
            LeafType(graphqlType, null)
        }
        else -> error("Input type '$graphqlType' is not a built-in scalar, has no custom-scalar mapping, and is not an enum or input object.")
    }

    private fun scalarLeaf(name: String): LeafType {
        val mapping = scalars.getValue(name)
        extraImports += mapping.imports
        mapping.serializerWith?.let { fileSerializers += it } // applied via the file's @file:UseSerializers header
        return LeafType(mapping.kotlinType, mapping.serializerWith)
    }

    // ---- type-reference rendering ----

    private fun kotlinTypeRef(type: Type, base: String): String = when (type) {
        is NonNullType -> renderNonNull(type.type, base)
        is ListType -> "List<${kotlinTypeRef(type.type, base)}>?"
        is NamedType -> "$base?"
    }

    private fun renderNonNull(type: Type, base: String): String = when (type) {
        is ListType -> "List<${kotlinTypeRef(type.type, base)}>"
        else -> base // a named type under non-null; GraphQL has no non-null-of-non-null, so nothing else reaches here
    }

    // Operation/type/field names are never empty, so index directly — avoids replaceFirstChar's dead empty-string arm.
    private fun pascalCase(name: String): String = name[0].uppercaseChar() + name.substring(1)

    /** The single-line GraphQL document to send: this operation + its fragments, with `__typename` injected into polymorphic selections, printed by the foundation [GraphQLPrinter]. */
    private fun printedDocument(operation: OperationDefinition): String =
        GraphQLPrinter.printCompact(Document(listOf(operation) + fragments.values).withInjectedTypenames())

    private fun kotlinString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    companion object {
        /** Prefix for a generated per-GraphQL-type interface (e.g. `Metadata` → `IMetadata`). */
        const val INTERFACE_PREFIX: String = "I"

        /** The five spec built-in scalars. Custom scalars are added via the constructor's `scalarMappings`. */
        private val BUILT_IN_SCALARS: Map<String, ScalarMapping> = mapOf(
            "Int" to ScalarMapping("Int"),
            "Float" to ScalarMapping("Double"),
            "String" to ScalarMapping("String"),
            "Boolean" to ScalarMapping("Boolean"),
            "ID" to ScalarMapping("String"),
        )
    }
}
