package bosca.graphql.printer

import bosca.graphql.language.Argument
import bosca.graphql.language.BooleanValue
import bosca.graphql.language.Definition
import bosca.graphql.language.Directive
import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumTypeExtension
import bosca.graphql.language.EnumValue
import bosca.graphql.language.EnumValueDefinition
import bosca.graphql.language.ExecutableDefinition
import bosca.graphql.language.Field
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.FloatValue
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InputObjectTypeExtension
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.InterfaceTypeExtension
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.Node
import bosca.graphql.language.NonNullType
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectField
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectTypeExtension
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.OperationTypeDefinition
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.ScalarTypeExtension
import bosca.graphql.language.SchemaDefinition
import bosca.graphql.language.SchemaExtension
import bosca.graphql.language.Selection
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.StringValue
import bosca.graphql.language.Type
import bosca.graphql.language.TypeSystemDefinition
import bosca.graphql.language.TypeSystemExtension
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.UnionTypeExtension
import bosca.graphql.language.Value
import bosca.graphql.language.Variable
import bosca.graphql.language.VariableDefinition

/**
 * Renders the AST back to text — the inverse of the parser. Two styles:
 * - [print]: canonical, multi-line, two-space-indented SDL / operations (for schema printing, debugging).
 * - [printCompact]: a single-line form (for embedding operation documents in generated clients / wire payloads).
 *
 * Both are deterministic and re-parseable: `printCompact(parse(printCompact(parse(x))))` is a fixed point.
 * Faithful — it adds nothing the AST doesn't carry (e.g. `__typename` injection is a codegen concern, not the
 * printer's). `commonMain`, no dependencies.
 */
object GraphQLPrinter {

    private const val INDENT = "  "

    /** Canonical, multi-line text. */
    fun print(node: Node): String = render(node, "", compact = false)

    /** Single-line text. */
    fun printCompact(node: Node): String = render(node, "", compact = true)

    private fun render(node: Node, indent: String, compact: Boolean): String = when (node) {
        is Document -> node.definitions.joinToString(if (compact) " " else "\n\n") { render(it, indent, compact) }
        is Definition -> definition(node, indent, compact)
        is SelectionSet -> selectionSet(node, indent, compact)
        is Selection -> selection(node, indent, compact)
        is Value -> value(node)
        is Type -> type(node)
        is Argument -> "${node.name}: ${value(node.value)}"
        is Directive -> "@${node.name}${arguments(node.arguments)}"
        is VariableDefinition -> variableDefinition(node)
        is OperationTypeDefinition -> "${node.operation.name.lowercase()}: ${node.type.name}"
        is FieldDefinition -> fieldDefinition(node, indent, compact, inline = false)
        is InputValueDefinition -> inputValueDefinition(node, indent, compact, inline = false)
        is EnumValueDefinition -> describe(node.description, indent, compact) + node.name + directives(node.directives)
        is ObjectField -> "${node.name}: ${value(node.value)}"
    }

    // ---- executable ----

    private fun definition(node: Definition, indent: String, compact: Boolean): String = when (node) {
        is ExecutableDefinition -> executable(node, indent, compact)
        is TypeSystemDefinition -> typeSystem(node, indent, compact)
        is TypeSystemExtension -> extension(node, indent, compact)
    }

    private fun executable(node: ExecutableDefinition, indent: String, compact: Boolean): String = when (node) {
        is OperationDefinition -> operation(node, indent, compact)
        is FragmentDefinition ->
            "fragment ${node.name} on ${node.typeCondition.name}${directives(node.directives)} " +
                selectionSet(node.selectionSet, indent, compact)
    }

    private fun operation(node: OperationDefinition, indent: String, compact: Boolean): String {
        val anonymousShorthand = node.operation == OperationType.QUERY &&
            node.name == null && node.variableDefinitions.isEmpty() && node.directives.isEmpty()
        if (anonymousShorthand) return selectionSet(node.selectionSet, indent, compact)

        val variables = if (node.variableDefinitions.isEmpty()) {
            ""
        } else {
            "(" + node.variableDefinitions.joinToString(", ") { variableDefinition(it) } + ")"
        }
        return node.operation.name.lowercase() +
            node.name?.let { " $it" }.orEmpty() +
            variables +
            directives(node.directives) +
            " " + selectionSet(node.selectionSet, indent, compact)
    }

    private fun variableDefinition(node: VariableDefinition): String =
        "$${node.variable.name}: ${type(node.type)}" +
            node.defaultValue?.let { " = ${value(it)}" }.orEmpty() +
            directives(node.directives)

    private fun selectionSet(node: SelectionSet, indent: String, compact: Boolean): String {
        if (node.selections.isEmpty()) return "{}"
        if (compact) return "{ " + node.selections.joinToString(" ") { selection(it, indent, true) } + " }"
        val child = indent + INDENT
        return "{\n" + node.selections.joinToString("\n") { child + selection(it, child, false) } + "\n$indent}"
    }

    private fun selection(node: Selection, indent: String, compact: Boolean): String = when (node) {
        is Field -> {
            val head = node.alias?.let { "$it: " }.orEmpty() + node.name + arguments(node.arguments) + directives(node.directives)
            val sub = node.selectionSet
            if (sub != null) "$head ${selectionSet(sub, indent, compact)}" else head
        }
        is FragmentSpread -> "...${node.name}${directives(node.directives)}"
        is InlineFragment ->
            "..." + node.typeCondition?.let { " on ${it.name}" }.orEmpty() + directives(node.directives) +
                " " + selectionSet(node.selectionSet, indent, compact)
    }

    // ---- type-system (SDL) ----

    private fun typeSystem(node: TypeSystemDefinition, indent: String, compact: Boolean): String = when (node) {
        is SchemaDefinition ->
            describe(node.description, indent, compact) + "schema" + directives(node.directives) +
                fieldBlock(node.operationTypes.map { render(it, "", compact) }, indent, compact)
        is ScalarTypeDefinition ->
            describe(node.description, indent, compact) + "scalar ${node.name}" + directives(node.directives)
        is ObjectTypeDefinition ->
            describe(node.description, indent, compact) + "type ${node.name}" + implementsClause(node.interfaces) +
                directives(node.directives) + fieldBlock(node.fields.map { fieldDefinition(it, childIndent(indent, compact), compact, inline = false) }, indent, compact)
        is InterfaceTypeDefinition ->
            describe(node.description, indent, compact) + "interface ${node.name}" + implementsClause(node.interfaces) +
                directives(node.directives) + fieldBlock(node.fields.map { fieldDefinition(it, childIndent(indent, compact), compact, inline = false) }, indent, compact)
        is UnionTypeDefinition ->
            describe(node.description, indent, compact) + "union ${node.name}" + directives(node.directives) + unionMembers(node.types)
        is EnumTypeDefinition ->
            describe(node.description, indent, compact) + "enum ${node.name}" + directives(node.directives) +
                fieldBlock(node.values.map { render(it, childIndent(indent, compact), compact) }, indent, compact)
        is InputObjectTypeDefinition ->
            describe(node.description, indent, compact) + "input ${node.name}" + directives(node.directives) +
                fieldBlock(node.fields.map { inputValueDefinition(it, childIndent(indent, compact), compact, inline = false) }, indent, compact)
        is DirectiveDefinition ->
            describe(node.description, indent, compact) + "directive @${node.name}" + argumentDefinitions(node.arguments, indent, compact) +
                (if (node.repeatable) " repeatable" else "") + " on " + node.locations.joinToString(" | ")
    }

    private fun extension(node: TypeSystemExtension, indent: String, compact: Boolean): String = "extend " + when (node) {
        is SchemaExtension -> "schema" + directives(node.directives) + fieldBlock(node.operationTypes.map { render(it, "", compact) }, indent, compact)
        is ScalarTypeExtension -> "scalar ${node.name}" + directives(node.directives)
        is ObjectTypeExtension -> "type ${node.name}" + implementsClause(node.interfaces) + directives(node.directives) +
            fieldBlock(node.fields.map { fieldDefinition(it, childIndent(indent, compact), compact, inline = false) }, indent, compact)
        is InterfaceTypeExtension -> "interface ${node.name}" + implementsClause(node.interfaces) + directives(node.directives) +
            fieldBlock(node.fields.map { fieldDefinition(it, childIndent(indent, compact), compact, inline = false) }, indent, compact)
        is UnionTypeExtension -> "union ${node.name}" + directives(node.directives) + unionMembers(node.types)
        is EnumTypeExtension -> "enum ${node.name}" + directives(node.directives) +
            fieldBlock(node.values.map { render(it, childIndent(indent, compact), compact) }, indent, compact)
        is InputObjectTypeExtension -> "input ${node.name}" + directives(node.directives) +
            fieldBlock(node.fields.map { inputValueDefinition(it, childIndent(indent, compact), compact, inline = false) }, indent, compact)
    }

    private fun fieldDefinition(node: FieldDefinition, indent: String, compact: Boolean, inline: Boolean): String =
        describeMaybeInline(node.description, indent, compact, inline) + node.name +
            argumentDefinitions(node.arguments, indent, compact) + ": " + type(node.type) + directives(node.directives)

    private fun inputValueDefinition(node: InputValueDefinition, indent: String, compact: Boolean, inline: Boolean): String =
        describeMaybeInline(node.description, indent, compact, inline) + node.name + ": " + type(node.type) +
            node.defaultValue?.let { " = ${value(it)}" }.orEmpty() + directives(node.directives)

    private fun argumentDefinitions(args: List<InputValueDefinition>, indent: String, compact: Boolean): String =
        if (args.isEmpty()) "" else "(" + args.joinToString(", ") { inputValueDefinition(it, indent, compact, inline = true) } + ")"

    private fun implementsClause(interfaces: List<NamedType>): String =
        if (interfaces.isEmpty()) "" else " implements " + interfaces.joinToString(" & ") { it.name }

    private fun unionMembers(types: List<NamedType>): String =
        if (types.isEmpty()) "" else " = " + types.joinToString(" | ") { it.name }

    private fun childIndent(indent: String, compact: Boolean): String = if (compact) indent else indent + INDENT

    /** A `{ … }` block of pre-rendered member strings; omitted entirely when empty (a fieldless type prints no braces). */
    private fun fieldBlock(members: List<String>, indent: String, compact: Boolean): String {
        if (members.isEmpty()) return ""
        if (compact) return " { " + members.joinToString(" ") + " }"
        return " {\n" + members.joinToString("\n") { "$indent$INDENT$it" } + "\n$indent}"
    }

    // ---- shared ----

    private fun arguments(args: List<Argument>): String =
        if (args.isEmpty()) "" else "(" + args.joinToString(", ") { "${it.name}: ${value(it.value)}" } + ")"

    private fun directives(directives: List<Directive>): String =
        if (directives.isEmpty()) "" else " " + directives.joinToString(" ") { "@${it.name}${arguments(it.arguments)}" }

    /** A description on its own line (pretty) or followed by a space (compact). */
    private fun describe(description: String?, indent: String, compact: Boolean): String =
        if (description == null) "" else stringLiteral(description) + if (compact) " " else "\n$indent"

    /** A description forced inline (for arguments, which stay on one line even in pretty mode). */
    private fun describeMaybeInline(description: String?, indent: String, compact: Boolean, inline: Boolean): String =
        if (inline) description?.let { "${stringLiteral(it)} " }.orEmpty() else describe(description, indent, compact)

    private fun type(node: Type): String = when (node) {
        is NamedType -> node.name
        is ListType -> "[${type(node.type)}]"
        is NonNullType -> "${type(node.type)}!"
    }

    private fun value(node: Value): String = when (node) {
        is Variable -> "$${node.name}"
        is IntValue -> node.value
        is FloatValue -> node.value
        is StringValue -> if (node.block) blockStringLiteral(node.value) else stringLiteral(node.value)
        is BooleanValue -> node.value.toString()
        is NullValue -> "null"
        is EnumValue -> node.value
        is ListValue -> "[" + node.values.joinToString(", ") { value(it) } + "]"
        is ObjectValue -> "{" + node.fields.joinToString(", ") { "${it.name}: ${value(it.value)}" } + "}"
    }

    private fun stringLiteral(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            // Other control characters aren't valid unescaped source (§2.9.4), so emit a \uXXXX escape.
            else -> if (c < ' ') append("\\u" + c.code.toString(16).uppercase().padStart(4, '0')) else append(c)
        }
        append('"')
    }

    private fun blockStringLiteral(s: String): String = "\"\"\"" + s.replace("\"\"\"", "\\\"\"\"") + "\"\"\""
}
