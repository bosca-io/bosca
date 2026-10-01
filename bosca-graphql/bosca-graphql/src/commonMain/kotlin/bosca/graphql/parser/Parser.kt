package bosca.graphql.parser

import bosca.graphql.language.Argument
import bosca.graphql.language.Definition
import bosca.graphql.language.Directive
import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumTypeExtension
import bosca.graphql.language.EnumValueDefinition
import bosca.graphql.language.Field
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InputObjectTypeExtension
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.InterfaceTypeExtension
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
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
import bosca.graphql.language.SourceLocation
import bosca.graphql.language.Type
import bosca.graphql.language.TypeSystemDefinition
import bosca.graphql.language.TypeSystemExtension
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.UnionTypeExtension
import bosca.graphql.language.Value
import bosca.graphql.language.BooleanValue
import bosca.graphql.language.EnumValue
import bosca.graphql.language.FloatValue
import bosca.graphql.language.IntValue
import bosca.graphql.language.NullValue
import bosca.graphql.language.StringValue
import bosca.graphql.language.Variable
import bosca.graphql.language.VariableDefinition

/**
 * A hand-written recursive-descent GraphQL parser (no graphql-java). Parses a full GraphQL [Document] —
 * both executable definitions (operations, fragments) and type-system definitions/extensions (SDL) —
 * into the [bosca.graphql.language] AST. Pure Kotlin, multiplatform.
 *
 * Use the [companion object][Companion]: `Parser.parse(source)` for a document, `Parser.parseValue(source)`
 * for a single value.
 */
class Parser private constructor(source: String, private val limits: ParserLimits) {

    private val lexer = Lexer(source)
    private var tokensRead = 0
    private var nestingDepth = 0

    init {
        nextToken()
    }

    companion object {
        /** Parses a complete GraphQL document (queries and/or SDL). */
        fun parse(source: String): Document = Parser(source, ParserLimits.DEFAULT).parseDocument()

        /** Parses a complete GraphQL document while enforcing the supplied parser work [limits]. */
        fun parse(source: String, limits: ParserLimits): Document = Parser(source, limits).parseDocument()

        /** Parses a single (non-constant) GraphQL value, e.g. for testing or default-value handling. */
        fun parseValue(source: String): Value {
            val parser = Parser(source, ParserLimits.DEFAULT)
            val value = parser.parseValue(const = false)
            parser.expect(TokenKind.EOF)
            return value
        }

        /** Parses one GraphQL value while enforcing the supplied parser work [limits]. */
        fun parseValue(source: String, limits: ParserLimits): Value {
            val parser = Parser(source, limits)
            val value = parser.parseValue(const = false)
            parser.expect(TokenKind.EOF)
            return value
        }
    }

    // ---- token cursor -------------------------------------------------------------------------------

    private val tokenKind: TokenKind
        get() = lexer.tokenKind
    private val tokenValue: String
        get() = lexer.tokenValue

    private fun nextToken() {
        lexer.advanceToken()
        if (tokenKind != TokenKind.EOF) {
            tokensRead++
            if (tokensRead > limits.maxTokens) {
                throw GraphQLSyntaxException(
                    "Document exceeds the maximum token count of ${limits.maxTokens}",
                    lexer.currentLocation(),
                )
            }
        }
    }

    private fun advance(): String {
        val value = tokenValue
        nextToken()
        return value
    }

    private fun at(kind: TokenKind): Boolean = tokenKind == kind
    private fun atName(name: String): Boolean = tokenKind == TokenKind.NAME && tokenValue == name

    private fun expect(kind: TokenKind): String {
        if (tokenKind != kind) fail("Expected ${kind.display}, found ${describe()}")
        return advance()
    }

    private fun expectKeyword(name: String) {
        if (!atName(name)) fail("Expected \"$name\", found ${describe()}")
        advance()
    }

    private fun location(): SourceLocation = lexer.currentLocation()

    private fun fail(message: String): Nothing = throw GraphQLSyntaxException(message, location())

    private inline fun <T> nested(block: () -> T): T {
        nestingDepth++
        if (nestingDepth > limits.maxNestingDepth) {
            nestingDepth--
            fail("Document exceeds the maximum nesting depth of ${limits.maxNestingDepth}")
        }
        return try {
            block()
        } finally {
            nestingDepth--
        }
    }

    private fun describe(kind: TokenKind = tokenKind, value: String = tokenValue): String = when (kind) {
        TokenKind.NAME -> "Name \"$value\""
        TokenKind.INT -> "Int \"$value\""
        TokenKind.FLOAT -> "Float \"$value\""
        TokenKind.STRING, TokenKind.BLOCK_STRING -> "String value"
        TokenKind.EOF -> "<EOF>"
        else -> "\"${kind.display}\""
    }

    // ---- document & definitions ---------------------------------------------------------------------

    private fun parseDocument(): Document {
        val loc = location()
        val definitions = mutableListOf<Definition>()
        while (!at(TokenKind.EOF)) definitions.add(parseDefinition())
        return Document(definitions, loc)
    }

    private fun parseDefinition(): Definition {
        if (at(TokenKind.BRACE_L)) return parseOperationDefinition() // query shorthand
        if (at(TokenKind.STRING) || at(TokenKind.BLOCK_STRING)) {
            val description = parseDescription()
            if (!at(TokenKind.NAME)) fail("Expected a type-system definition after description, found ${describe()}")
            return parseTypeSystemDefinition(description)
        }
        if (at(TokenKind.NAME)) {
            return when (tokenValue) {
                "query", "mutation", "subscription" -> parseOperationDefinition()
                "fragment" -> parseFragmentDefinition()
                "schema", "scalar", "type", "interface", "union", "enum", "input", "directive" ->
                    parseTypeSystemDefinition(null)
                "extend" -> parseTypeSystemExtension()
                else -> fail("Unexpected Name \"$tokenValue\"")
            }
        }
        fail("Unexpected ${describe()}")
    }

    private fun parseDescription(): String? =
        if (at(TokenKind.STRING) || at(TokenKind.BLOCK_STRING)) advance() else null

    // ---- executable definitions ---------------------------------------------------------------------

    private fun parseOperationDefinition(): OperationDefinition {
        val loc = location()
        if (at(TokenKind.BRACE_L)) {
            return OperationDefinition(OperationType.QUERY, null, emptyList(), emptyList(), parseSelectionSet(), loc)
        }
        val operation = parseOperationType()
        val name = if (at(TokenKind.NAME)) advance() else null
        val variableDefinitions = parseVariableDefinitions()
        val directives = parseDirectives(const = false)
        val selectionSet = parseSelectionSet()
        return OperationDefinition(operation, name, variableDefinitions, directives, selectionSet, loc)
    }

    private fun parseOperationType(): OperationType {
        val loc = location()
        val value = expect(TokenKind.NAME)
        return when (value) {
            "query" -> OperationType.QUERY
            "mutation" -> OperationType.MUTATION
            "subscription" -> OperationType.SUBSCRIPTION
            else -> throw GraphQLSyntaxException(
                "Expected operation type (query, mutation, or subscription), found Name \"$value\"",
                loc,
            )
        }
    }

    private fun parseFragmentDefinition(): FragmentDefinition {
        val loc = location()
        expectKeyword("fragment")
        val name = expect(TokenKind.NAME)
        if (name == "on") fail("A fragment cannot be named \"on\"")
        expectKeyword("on")
        val typeCondition = parseNamedType()
        val directives = parseDirectives(const = false)
        val selectionSet = parseSelectionSet()
        return FragmentDefinition(name, typeCondition, directives, selectionSet, loc)
    }

    private fun parseVariableDefinitions(): List<VariableDefinition> {
        if (!at(TokenKind.PAREN_L)) return emptyList()
        advance()
        val list = mutableListOf<VariableDefinition>()
        while (!at(TokenKind.PAREN_R)) list.add(parseVariableDefinition())
        expect(TokenKind.PAREN_R)
        return list
    }

    private fun parseVariableDefinition(): VariableDefinition {
        val loc = location()
        val variable = parseVariable()
        expect(TokenKind.COLON)
        val type = parseType()
        val defaultValue = if (at(TokenKind.EQUALS)) {
            advance()
            parseValue(const = true)
        } else {
            null
        }
        val directives = parseDirectives(const = true)
        return VariableDefinition(variable, type, defaultValue, directives, loc)
    }

    private fun parseVariable(): Variable {
        val loc = location()
        expect(TokenKind.DOLLAR)
        return Variable(expect(TokenKind.NAME), loc)
    }

    private fun parseSelectionSet(): SelectionSet = nested {
        val loc = location()
        expect(TokenKind.BRACE_L)
        val selections = mutableListOf<Selection>()
        while (!at(TokenKind.BRACE_R)) selections.add(parseSelection())
        expect(TokenKind.BRACE_R)
        SelectionSet(selections, loc)
    }

    private fun parseSelection(): Selection =
        if (at(TokenKind.SPREAD)) parseFragment() else parseField()

    private fun parseField(): Field {
        val loc = location()
        val nameOrAlias = expect(TokenKind.NAME)
        val alias: String?
        val name: String
        if (at(TokenKind.COLON)) {
            advance()
            alias = nameOrAlias
            name = expect(TokenKind.NAME)
        } else {
            alias = null
            name = nameOrAlias
        }
        val arguments = parseArguments(const = false)
        val directives = parseDirectives(const = false)
        val selectionSet = if (at(TokenKind.BRACE_L)) parseSelectionSet() else null
        return Field(alias, name, arguments, directives, selectionSet, loc)
    }

    private fun parseFragment(): Selection {
        val loc = location()
        expect(TokenKind.SPREAD)
        // `... name`  -> fragment spread (name must not be the keyword `on`)
        // `... on T`  -> inline fragment with a type condition
        // `... @d {}` / `... {}` -> inline fragment without a type condition
        if (at(TokenKind.NAME) && tokenValue != "on") {
            val name = advance()
            return FragmentSpread(name, parseDirectives(const = false), loc)
        }
        val typeCondition = if (atName("on")) {
            advance()
            parseNamedType()
        } else {
            null
        }
        val directives = parseDirectives(const = false)
        val selectionSet = parseSelectionSet()
        return InlineFragment(typeCondition, directives, selectionSet, loc)
    }

    private fun parseArguments(const: Boolean): List<Argument> {
        if (!at(TokenKind.PAREN_L)) return emptyList()
        advance()
        val list = mutableListOf<Argument>()
        while (!at(TokenKind.PAREN_R)) {
            val loc = location()
            val name = expect(TokenKind.NAME)
            expect(TokenKind.COLON)
            list.add(Argument(name, parseValue(const), loc))
        }
        expect(TokenKind.PAREN_R)
        return list
    }

    private fun parseDirectives(const: Boolean): List<Directive> {
        if (!at(TokenKind.AT)) return emptyList()
        val list = mutableListOf<Directive>()
        while (at(TokenKind.AT)) {
            val loc = location()
            advance()
            val name = expect(TokenKind.NAME)
            list.add(Directive(name, parseArguments(const), loc))
        }
        return list
    }

    // ---- values -------------------------------------------------------------------------------------

    private fun parseValue(const: Boolean): Value {
        val kind = tokenKind
        val value = tokenValue
        val loc = location()
        return when (kind) {
            TokenKind.BRACKET_L -> parseListValue(const)
            TokenKind.BRACE_L -> parseObjectValue(const)
            TokenKind.DOLLAR -> {
                if (const) fail("Unexpected variable in a constant value")
                parseVariable()
            }
            TokenKind.INT -> {
                advance()
                IntValue(value, loc)
            }
            TokenKind.FLOAT -> {
                advance()
                FloatValue(value, loc)
            }
            TokenKind.STRING -> {
                advance()
                StringValue(value, block = false, location = loc)
            }
            TokenKind.BLOCK_STRING -> {
                advance()
                StringValue(value, block = true, location = loc)
            }
            TokenKind.NAME -> {
                advance()
                when (value) {
                    "true" -> BooleanValue(true, loc)
                    "false" -> BooleanValue(false, loc)
                    "null" -> NullValue(loc)
                    else -> EnumValue(value, loc)
                }
            }
            else -> fail("Unexpected ${describe(kind, value)}")
        }
    }

    private fun parseListValue(const: Boolean): Value = nested {
        val loc = location()
        expect(TokenKind.BRACKET_L)
        val values = mutableListOf<Value>()
        while (!at(TokenKind.BRACKET_R)) values.add(parseValue(const))
        expect(TokenKind.BRACKET_R)
        ListValue(values, loc)
    }

    private fun parseObjectValue(const: Boolean): Value = nested {
        val loc = location()
        expect(TokenKind.BRACE_L)
        val fields = mutableListOf<ObjectField>()
        while (!at(TokenKind.BRACE_R)) {
            val floc = location()
            val name = expect(TokenKind.NAME)
            expect(TokenKind.COLON)
            fields.add(ObjectField(name, parseValue(const), floc))
        }
        expect(TokenKind.BRACE_R)
        ObjectValue(fields, loc)
    }

    // ---- type references ----------------------------------------------------------------------------

    private fun parseType(): Type = nested {
        val loc = location()
        val type: Type = if (at(TokenKind.BRACKET_L)) {
            advance()
            val inner = parseType()
            expect(TokenKind.BRACKET_R)
            ListType(inner, loc)
        } else {
            parseNamedType()
        }
        if (at(TokenKind.BANG)) {
            advance()
            NonNullType(type, loc)
        } else {
            type
        }
    }

    private fun parseNamedType(): NamedType {
        val loc = location()
        return NamedType(expect(TokenKind.NAME), loc)
    }

    // ---- type system definitions --------------------------------------------------------------------

    private fun parseTypeSystemDefinition(description: String?): TypeSystemDefinition = when (tokenValue) {
        "schema" -> parseSchemaDefinition(description)
        "scalar" -> parseScalarTypeDefinition(description)
        "type" -> parseObjectTypeDefinition(description)
        "interface" -> parseInterfaceTypeDefinition(description)
        "union" -> parseUnionTypeDefinition(description)
        "enum" -> parseEnumTypeDefinition(description)
        "input" -> parseInputObjectTypeDefinition(description)
        "directive" -> parseDirectiveDefinition(description)
        else -> fail("Unexpected Name \"$tokenValue\"")
    }

    private fun parseSchemaDefinition(description: String?): SchemaDefinition {
        val loc = location()
        expectKeyword("schema")
        val directives = parseDirectives(const = true)
        expect(TokenKind.BRACE_L)
        val ops = mutableListOf<OperationTypeDefinition>()
        while (!at(TokenKind.BRACE_R)) ops.add(parseOperationTypeDefinition())
        expect(TokenKind.BRACE_R)
        return SchemaDefinition(description, directives, ops, loc)
    }

    private fun parseOperationTypeDefinition(): OperationTypeDefinition {
        val loc = location()
        val operation = parseOperationType()
        expect(TokenKind.COLON)
        return OperationTypeDefinition(operation, parseNamedType(), loc)
    }

    private fun parseScalarTypeDefinition(description: String?): ScalarTypeDefinition {
        val loc = location()
        expectKeyword("scalar")
        val name = expect(TokenKind.NAME)
        return ScalarTypeDefinition(description, name, parseDirectives(const = true), loc)
    }

    private fun parseObjectTypeDefinition(description: String?): ObjectTypeDefinition {
        val loc = location()
        expectKeyword("type")
        val name = expect(TokenKind.NAME)
        val interfaces = parseImplementsInterfaces()
        val directives = parseDirectives(const = true)
        val fields = parseFieldsDefinition()
        return ObjectTypeDefinition(description, name, interfaces, directives, fields, loc)
    }

    private fun parseInterfaceTypeDefinition(description: String?): InterfaceTypeDefinition {
        val loc = location()
        expectKeyword("interface")
        val name = expect(TokenKind.NAME)
        val interfaces = parseImplementsInterfaces()
        val directives = parseDirectives(const = true)
        val fields = parseFieldsDefinition()
        return InterfaceTypeDefinition(description, name, interfaces, directives, fields, loc)
    }

    private fun parseUnionTypeDefinition(description: String?): UnionTypeDefinition {
        val loc = location()
        expectKeyword("union")
        val name = expect(TokenKind.NAME)
        val directives = parseDirectives(const = true)
        val types = parseUnionMemberTypes()
        return UnionTypeDefinition(description, name, directives, types, loc)
    }

    private fun parseEnumTypeDefinition(description: String?): EnumTypeDefinition {
        val loc = location()
        expectKeyword("enum")
        val name = expect(TokenKind.NAME)
        val directives = parseDirectives(const = true)
        val values = parseEnumValuesDefinition()
        return EnumTypeDefinition(description, name, directives, values, loc)
    }

    private fun parseInputObjectTypeDefinition(description: String?): InputObjectTypeDefinition {
        val loc = location()
        expectKeyword("input")
        val name = expect(TokenKind.NAME)
        val directives = parseDirectives(const = true)
        val fields = parseInputFieldsDefinition()
        return InputObjectTypeDefinition(description, name, directives, fields, loc)
    }

    private fun parseDirectiveDefinition(description: String?): DirectiveDefinition {
        val loc = location()
        expectKeyword("directive")
        expect(TokenKind.AT)
        val name = expect(TokenKind.NAME)
        val arguments = parseArgumentsDefinition()
        val repeatable = if (atName("repeatable")) {
            advance()
            true
        } else {
            false
        }
        expectKeyword("on")
        return DirectiveDefinition(description, name, arguments, repeatable, parseDirectiveLocations(), loc)
    }

    private fun parseDirectiveLocations(): List<String> {
        val list = mutableListOf<String>()
        if (at(TokenKind.PIPE)) advance()
        list.add(expect(TokenKind.NAME))
        while (at(TokenKind.PIPE)) {
            advance()
            list.add(expect(TokenKind.NAME))
        }
        return list
    }

    private fun parseImplementsInterfaces(): List<NamedType> {
        if (!atName("implements")) return emptyList()
        advance()
        val list = mutableListOf<NamedType>()
        if (at(TokenKind.AMP)) advance()
        list.add(parseNamedType())
        while (at(TokenKind.AMP)) {
            advance()
            list.add(parseNamedType())
        }
        return list
    }

    private fun parseUnionMemberTypes(): List<NamedType> {
        if (!at(TokenKind.EQUALS)) return emptyList()
        advance()
        val list = mutableListOf<NamedType>()
        if (at(TokenKind.PIPE)) advance()
        list.add(parseNamedType())
        while (at(TokenKind.PIPE)) {
            advance()
            list.add(parseNamedType())
        }
        return list
    }

    private fun parseFieldsDefinition(): List<FieldDefinition> {
        if (!at(TokenKind.BRACE_L)) return emptyList()
        advance()
        val list = mutableListOf<FieldDefinition>()
        while (!at(TokenKind.BRACE_R)) list.add(parseFieldDefinition())
        expect(TokenKind.BRACE_R)
        return list
    }

    private fun parseFieldDefinition(): FieldDefinition {
        val loc = location()
        val description = parseDescription()
        val name = expect(TokenKind.NAME)
        val arguments = parseArgumentsDefinition()
        expect(TokenKind.COLON)
        val type = parseType()
        val directives = parseDirectives(const = true)
        return FieldDefinition(description, name, arguments, type, directives, loc)
    }

    private fun parseArgumentsDefinition(): List<InputValueDefinition> {
        if (!at(TokenKind.PAREN_L)) return emptyList()
        advance()
        val list = mutableListOf<InputValueDefinition>()
        while (!at(TokenKind.PAREN_R)) list.add(parseInputValueDefinition())
        expect(TokenKind.PAREN_R)
        return list
    }

    private fun parseInputFieldsDefinition(): List<InputValueDefinition> {
        if (!at(TokenKind.BRACE_L)) return emptyList()
        advance()
        val list = mutableListOf<InputValueDefinition>()
        while (!at(TokenKind.BRACE_R)) list.add(parseInputValueDefinition())
        expect(TokenKind.BRACE_R)
        return list
    }

    private fun parseInputValueDefinition(): InputValueDefinition {
        val loc = location()
        val description = parseDescription()
        val name = expect(TokenKind.NAME)
        expect(TokenKind.COLON)
        val type = parseType()
        val defaultValue = if (at(TokenKind.EQUALS)) {
            advance()
            parseValue(const = true)
        } else {
            null
        }
        val directives = parseDirectives(const = true)
        return InputValueDefinition(description, name, type, defaultValue, directives, loc)
    }

    private fun parseEnumValuesDefinition(): List<EnumValueDefinition> {
        if (!at(TokenKind.BRACE_L)) return emptyList()
        advance()
        val list = mutableListOf<EnumValueDefinition>()
        while (!at(TokenKind.BRACE_R)) {
            val loc = location()
            val description = parseDescription()
            val name = expect(TokenKind.NAME)
            list.add(EnumValueDefinition(description, name, parseDirectives(const = true), loc))
        }
        expect(TokenKind.BRACE_R)
        return list
    }

    // ---- type system extensions ---------------------------------------------------------------------

    private fun parseTypeSystemExtension(): TypeSystemExtension {
        val loc = location()
        expectKeyword("extend")
        if (!at(TokenKind.NAME)) fail("Unexpected ${describe()} after \"extend\"")
        return when (tokenValue) {
            "schema" -> parseSchemaExtension(loc)
            "scalar" -> parseScalarTypeExtension(loc)
            "type" -> parseObjectTypeExtension(loc)
            "interface" -> parseInterfaceTypeExtension(loc)
            "union" -> parseUnionTypeExtension(loc)
            "enum" -> parseEnumTypeExtension(loc)
            "input" -> parseInputObjectTypeExtension(loc)
            else -> fail("Unexpected Name \"$tokenValue\" after \"extend\"")
        }
    }

    private fun parseSchemaExtension(loc: SourceLocation): SchemaExtension {
        expectKeyword("schema")
        val directives = parseDirectives(const = true)
        val ops = if (at(TokenKind.BRACE_L)) {
            advance()
            val list = mutableListOf<OperationTypeDefinition>()
            while (!at(TokenKind.BRACE_R)) list.add(parseOperationTypeDefinition())
            expect(TokenKind.BRACE_R)
            list
        } else {
            emptyList()
        }
        return SchemaExtension(directives, ops, loc)
    }

    private fun parseScalarTypeExtension(loc: SourceLocation): ScalarTypeExtension {
        expectKeyword("scalar")
        val name = expect(TokenKind.NAME)
        return ScalarTypeExtension(name, parseDirectives(const = true), loc)
    }

    private fun parseObjectTypeExtension(loc: SourceLocation): ObjectTypeExtension {
        expectKeyword("type")
        val name = expect(TokenKind.NAME)
        val interfaces = parseImplementsInterfaces()
        val directives = parseDirectives(const = true)
        val fields = parseFieldsDefinition()
        return ObjectTypeExtension(name, interfaces, directives, fields, loc)
    }

    private fun parseInterfaceTypeExtension(loc: SourceLocation): InterfaceTypeExtension {
        expectKeyword("interface")
        val name = expect(TokenKind.NAME)
        val interfaces = parseImplementsInterfaces()
        val directives = parseDirectives(const = true)
        val fields = parseFieldsDefinition()
        return InterfaceTypeExtension(name, interfaces, directives, fields, loc)
    }

    private fun parseUnionTypeExtension(loc: SourceLocation): UnionTypeExtension {
        expectKeyword("union")
        val name = expect(TokenKind.NAME)
        val directives = parseDirectives(const = true)
        val types = parseUnionMemberTypes()
        return UnionTypeExtension(name, directives, types, loc)
    }

    private fun parseEnumTypeExtension(loc: SourceLocation): EnumTypeExtension {
        expectKeyword("enum")
        val name = expect(TokenKind.NAME)
        val directives = parseDirectives(const = true)
        val values = parseEnumValuesDefinition()
        return EnumTypeExtension(name, directives, values, loc)
    }

    private fun parseInputObjectTypeExtension(loc: SourceLocation): InputObjectTypeExtension {
        expectKeyword("input")
        val name = expect(TokenKind.NAME)
        val directives = parseDirectives(const = true)
        val fields = parseInputFieldsDefinition()
        return InputObjectTypeExtension(name, directives, fields, loc)
    }
}
