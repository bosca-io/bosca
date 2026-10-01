package bosca.graphql.schema

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.Directive
import bosca.graphql.language.Document
import bosca.graphql.language.DirectiveDefinition
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumTypeExtension
import bosca.graphql.language.EnumValue
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.FloatValue
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InputObjectTypeExtension
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.InterfaceTypeExtension
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectTypeExtension
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.OperationType
import bosca.graphql.language.OperationTypeDefinition
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.ScalarTypeExtension
import bosca.graphql.language.SchemaDefinition
import bosca.graphql.language.SchemaExtension
import bosca.graphql.language.StringValue
import bosca.graphql.language.Type
import bosca.graphql.language.TypeDefinition
import bosca.graphql.language.TypeSystemExtension
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.UnionTypeExtension
import bosca.graphql.language.Value
import bosca.graphql.parser.Parser

/**
 * Assembles a [GraphQLSchema] from a parsed SDL [Document]: collect definitions, inject the spec
 * built-ins, merge `extend` extensions, resolve the root operation types, and validate references.
 */
internal object SchemaBuilder {

    // The built-ins every schema must provide, expressed as SDL and parsed by our own parser.
    private val BUILT_IN_SDL = """
        scalar Int
        scalar Float
        scalar String
        scalar Boolean
        scalar ID
        directive @skip(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
        directive @include(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
        directive @deprecated(reason: String = "No longer supported") on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE
        directive @specifiedBy(url: String!) on SCALAR
        directive @oneOf on INPUT_OBJECT
    """.trimIndent()

    // The valid `__DirectiveLocation` names (§3.13): executable locations + type-system locations.
    private val VALID_DIRECTIVE_LOCATIONS = setOf(
        "QUERY", "MUTATION", "SUBSCRIPTION", "FIELD", "FRAGMENT_DEFINITION", "FRAGMENT_SPREAD",
        "INLINE_FRAGMENT", "VARIABLE_DEFINITION",
        "SCHEMA", "SCALAR", "OBJECT", "FIELD_DEFINITION", "ARGUMENT_DEFINITION", "INTERFACE",
        "UNION", "ENUM", "ENUM_VALUE", "INPUT_OBJECT", "INPUT_FIELD_DEFINITION",
    )

    fun build(document: Document): GraphQLSchema {
        val types = LinkedHashMap<String, TypeDefinition>()
        val directives = LinkedHashMap<String, DirectiveDefinition>()
        var hasSchemaDefinition = false
        var schemaDescription: String? = null
        val schemaDirectives = mutableListOf<Directive>()
        val operationTypes = mutableListOf<OperationTypeDefinition>()
        val extensions = mutableListOf<TypeSystemExtension>()

        for (def in document.definitions) {
            when (def) {
                is TypeDefinition -> {
                    if (types.containsKey(def.name)) throw SchemaException("Duplicate type definition '${def.name}'")
                    types[def.name] = def
                }
                is DirectiveDefinition -> {
                    if (directives.containsKey(def.name)) throw SchemaException("Duplicate directive definition '@${def.name}'")
                    directives[def.name] = def
                }
                is SchemaDefinition -> {
                    if (hasSchemaDefinition) throw SchemaException("A document may define the schema only once")
                    hasSchemaDefinition = true
                    schemaDescription = def.description
                    schemaDirectives += def.directives
                    operationTypes += def.operationTypes
                }
                is SchemaExtension -> {
                    schemaDirectives += def.directives
                    operationTypes += def.operationTypes
                    extensions += def // also runs through applyExtension (a no-op for schema extensions)
                }
                is TypeSystemExtension -> extensions += def
                else -> Unit // executable definitions are not part of a schema
            }
        }

        injectBuiltIns(types, directives)
        extensions.forEach { applyExtension(it, types) }
        val roots = resolveRoots(operationTypes, types)
        validate(types, directives, roots)
        requireDirectivesAllowed(schemaDirectives, "SCHEMA", "the schema", directives)
        return GraphQLSchema(types, directives, roots.query, roots.mutation, roots.subscription, schemaDescription)
    }

    private fun injectBuiltIns(
        types: MutableMap<String, TypeDefinition>,
        directives: MutableMap<String, DirectiveDefinition>,
    ) {
        val builtIns = Parser.parse(BUILT_IN_SDL).definitions
        builtIns.filterIsInstance<ScalarTypeDefinition>().forEach { if (!types.containsKey(it.name)) types[it.name] = it }
        builtIns.filterIsInstance<DirectiveDefinition>().forEach { if (!directives.containsKey(it.name)) directives[it.name] = it }
    }

    private fun applyExtension(ext: TypeSystemExtension, types: MutableMap<String, TypeDefinition>) {
        when (ext) {
            is ObjectTypeExtension -> {
                val base = types[ext.name] as? ObjectTypeDefinition
                    ?: throw SchemaException("Cannot extend '${ext.name}': no such object type")
                types[ext.name] = base.copy(
                    interfaces = base.interfaces + ext.interfaces,
                    directives = base.directives + ext.directives,
                    fields = base.fields + ext.fields,
                )
            }
            is InterfaceTypeExtension -> {
                val base = types[ext.name] as? InterfaceTypeDefinition
                    ?: throw SchemaException("Cannot extend '${ext.name}': no such interface type")
                types[ext.name] = base.copy(
                    interfaces = base.interfaces + ext.interfaces,
                    directives = base.directives + ext.directives,
                    fields = base.fields + ext.fields,
                )
            }
            is UnionTypeExtension -> {
                val base = types[ext.name] as? UnionTypeDefinition
                    ?: throw SchemaException("Cannot extend '${ext.name}': no such union type")
                types[ext.name] = base.copy(directives = base.directives + ext.directives, types = base.types + ext.types)
            }
            is EnumTypeExtension -> {
                val base = types[ext.name] as? EnumTypeDefinition
                    ?: throw SchemaException("Cannot extend '${ext.name}': no such enum type")
                types[ext.name] = base.copy(directives = base.directives + ext.directives, values = base.values + ext.values)
            }
            is InputObjectTypeExtension -> {
                val base = types[ext.name] as? InputObjectTypeDefinition
                    ?: throw SchemaException("Cannot extend '${ext.name}': no such input object type")
                types[ext.name] = base.copy(directives = base.directives + ext.directives, fields = base.fields + ext.fields)
            }
            is ScalarTypeExtension -> {
                val base = types[ext.name] as? ScalarTypeDefinition
                    ?: throw SchemaException("Cannot extend '${ext.name}': no such scalar type")
                types[ext.name] = base.copy(directives = base.directives + ext.directives)
            }
            is SchemaExtension -> Unit // its operation types were folded in during collection
        }
    }

    private data class Roots(val query: String?, val mutation: String?, val subscription: String?)

    private fun resolveRoots(ops: List<OperationTypeDefinition>, types: Map<String, TypeDefinition>): Roots {
        if (ops.isNotEmpty()) {
            val byOperation = ops.associate { it.operation to it.type.name }
            return Roots(
                byOperation[OperationType.QUERY],
                byOperation[OperationType.MUTATION],
                byOperation[OperationType.SUBSCRIPTION],
            )
        }
        // No explicit `schema { … }` — fall back to the conventional root type names.
        fun conventional(name: String) = if (types[name] is ObjectTypeDefinition) name else null
        return Roots(conventional("Query"), conventional("Mutation"), conventional("Subscription"))
    }

    private fun validate(types: Map<String, TypeDefinition>, directives: Map<String, DirectiveDefinition>, roots: Roots) {
        val query = roots.query ?: throw SchemaException("Schema must define a query root type")
        requireObject(types, query, "query root type")
        roots.mutation?.let { requireObject(types, it, "mutation root type") }
        roots.subscription?.let { requireObject(types, it, "subscription root type") }

        for (t in types.values) {
            requireUnreserved(t.name, "Type")
            when (t) {
                is ObjectTypeDefinition -> validateComposite(types, t.name, t.fields, t.interfaces)
                is InterfaceTypeDefinition -> validateComposite(types, t.name, t.fields, t.interfaces)
                is UnionTypeDefinition -> {
                    requireUnique(t.types.map { it.name }, "Member") { "union '${t.name}'" }
                    t.types.forEach { requireObject(types, it.name, "union '${t.name}' member") }
                }
                is EnumTypeDefinition -> {
                    requireUnique(t.values.map { it.name }, "Enum value") { "enum '${t.name}'" }
                    t.values.forEach { requireUnreserved(it.name, "Enum value") }
                }
                is InputObjectTypeDefinition -> validateInputObject(types, t)
                else -> Unit // scalar types need no structural validation
            }
            validateDirectiveApplications(t, directives)
        }
        validateInputObjectCycles(types)
        directives.values.forEach { validateDirectiveDefinition(types, it) }
    }

    /**
     * §3.10: an input object must not reference itself through a chain of non-null, singular (non-list) fields —
     * such a value could never be constructed. A nullable field or a list anywhere in the chain breaks the cycle.
     */
    private fun validateInputObjectCycles(types: Map<String, TypeDefinition>) {
        val onStack = mutableSetOf<String>()
        val done = mutableSetOf<String>()

        fun visit(def: InputObjectTypeDefinition) {
            onStack += def.name
            for (field in def.fields) {
                val type = field.type
                if (type !is NonNullType || type.type !is NamedType) continue // nullable or list breaks the cycle
                val refDef = types[type.type.name]
                if (refDef !is InputObjectTypeDefinition) continue
                if (refDef.name in onStack) throw SchemaException("Input object '${def.name}' forms a non-null reference cycle via field '${field.name}'")
                if (refDef.name !in done) visit(refDef)
            }
            onStack -= def.name
            done += def.name
        }

        types.values.filterIsInstance<InputObjectTypeDefinition>().forEach { if (it.name !in done) visit(it) }
    }

    private fun validateComposite(
        types: Map<String, TypeDefinition>,
        owner: String,
        fields: List<FieldDefinition>,
        interfaces: List<NamedType>,
    ) {
        if (fields.isEmpty()) throw SchemaException("Type '$owner' must define one or more fields")
        requireUnique(fields.map { it.name }, "Field") { "type '$owner'" }
        requireUnique(interfaces.map { it.name }, "Interface") { "type '$owner'" }
        interfaces.forEach { requireInterface(types, it.name, "'$owner'") }
        interfaces.forEach { validateInterfaceImplementation(types, owner, fields, it.name) }
        // §3: a type implementing interface I must also declare every interface that I itself implements.
        for (named in interfaces) {
            val iface = types[named.name] as InterfaceTypeDefinition
            for (transitive in iface.interfaces) {
                if (interfaces.none { it.name == transitive.name }) {
                    throw SchemaException("Type '$owner' must also declare it implements '${transitive.name}' (required transitively via '${named.name}')")
                }
            }
        }
        for (field in fields) {
            requireUnreserved(field.name, "Field '$owner.${field.name}'")
            requireOutputType(types, underlyingTypeName(field.type), "field '$owner.${field.name}'")
            requireUnique(field.arguments.map { it.name }, "Argument") { "field '$owner.${field.name}'" }
            field.arguments.forEach { validateArgumentDefinition(types, it, "argument '$owner.${field.name}.${it.name}'") }
        }
    }

    private fun validateInputObject(types: Map<String, TypeDefinition>, type: InputObjectTypeDefinition) {
        if (type.fields.isEmpty()) throw SchemaException("Input object '${type.name}' must define one or more fields")
        requireUnique(type.fields.map { it.name }, "Field") { "input object '${type.name}'" }
        val isOneOf = type.directives.any { it.name == "oneOf" }
        for (field in type.fields) {
            requireUnreserved(field.name, "Input field '${type.name}.${field.name}'")
            requireInputType(types, underlyingTypeName(field.type), "input field '${type.name}.${field.name}'")
            requireValidDefault(types, field.defaultValue, field.type, "input field '${type.name}.${field.name}'")
            requireNotDeprecatedIfRequired(field.type, field.defaultValue, field.directives, "input field '${type.name}.${field.name}'")
            // A @oneOf input object's fields are mutually exclusive, so each must be nullable and default-free (§3.10).
            if (isOneOf && field.type is NonNullType) {
                throw SchemaException("OneOf input object '${type.name}' field '${field.name}' must be nullable")
            }
            if (isOneOf && field.defaultValue != null) {
                throw SchemaException("OneOf input object '${type.name}' field '${field.name}' must not have a default value")
            }
        }
    }

    private fun validateDirectiveDefinition(types: Map<String, TypeDefinition>, directive: DirectiveDefinition) {
        requireUnreserved(directive.name, "Directive '@${directive.name}'")
        requireUnique(directive.arguments.map { it.name }, "Argument") { "directive '@${directive.name}'" }
        directive.arguments.forEach { validateArgumentDefinition(types, it, "directive argument '@${directive.name}.${it.name}'") }
        directive.locations.forEach {
            if (it !in VALID_DIRECTIVE_LOCATIONS) throw SchemaException("Directive '@${directive.name}' declares unknown location '$it'")
        }
    }

    // ---- directive applications must occur only at the directive's declared locations (§3.13.1) ----

    private fun validateDirectiveApplications(type: TypeDefinition, directives: Map<String, DirectiveDefinition>) {
        when (type) {
            is ObjectTypeDefinition -> {
                requireDirectivesAllowed(type.directives, "OBJECT", "type '${type.name}'", directives)
                type.fields.forEach { validateFieldDirectives(type.name, it, directives) }
            }
            is InterfaceTypeDefinition -> {
                requireDirectivesAllowed(type.directives, "INTERFACE", "interface '${type.name}'", directives)
                type.fields.forEach { validateFieldDirectives(type.name, it, directives) }
            }
            is UnionTypeDefinition -> requireDirectivesAllowed(type.directives, "UNION", "union '${type.name}'", directives)
            is ScalarTypeDefinition -> requireDirectivesAllowed(type.directives, "SCALAR", "scalar '${type.name}'", directives)
            is EnumTypeDefinition -> {
                requireDirectivesAllowed(type.directives, "ENUM", "enum '${type.name}'", directives)
                type.values.forEach { requireDirectivesAllowed(it.directives, "ENUM_VALUE", "enum value '${type.name}.${it.name}'", directives) }
            }
            is InputObjectTypeDefinition -> {
                requireDirectivesAllowed(type.directives, "INPUT_OBJECT", "input object '${type.name}'", directives)
                type.fields.forEach { requireDirectivesAllowed(it.directives, "INPUT_FIELD_DEFINITION", "input field '${type.name}.${it.name}'", directives) }
            }
        }
    }

    private fun validateFieldDirectives(owner: String, field: FieldDefinition, directives: Map<String, DirectiveDefinition>) {
        requireDirectivesAllowed(field.directives, "FIELD_DEFINITION", "field '$owner.${field.name}'", directives)
        field.arguments.forEach {
            requireDirectivesAllowed(it.directives, "ARGUMENT_DEFINITION", "argument '$owner.${field.name}.${it.name}'", directives)
        }
    }

    private fun requireDirectivesAllowed(applied: List<Directive>, location: String, where: String, directives: Map<String, DirectiveDefinition>) {
        for (directive in applied) {
            val definition = directives[directive.name]
                ?: throw SchemaException("Unknown directive '@${directive.name}' on $where")
            if (location !in definition.locations) throw SchemaException("Directive '@${directive.name}' is not allowed on $where")
        }
    }

    private fun validateArgumentDefinition(types: Map<String, TypeDefinition>, arg: InputValueDefinition, where: String) {
        requireUnreserved(arg.name, where)
        requireInputType(types, underlyingTypeName(arg.type), where)
        requireValidDefault(types, arg.defaultValue, arg.type, where)
        requireNotDeprecatedIfRequired(arg.type, arg.defaultValue, arg.directives, where)
    }

    /** §3: a required (non-null, no-default) argument or input field must not be deprecated — clients must supply it. */
    private fun requireNotDeprecatedIfRequired(type: Type, defaultValue: Value?, directives: List<Directive>, where: String) {
        if (type is NonNullType && defaultValue == null && directives.any { it.name == "deprecated" }) {
            throw SchemaException("Required $where must not be deprecated")
        }
    }

    // ---- interface implementation completeness ----

    private fun validateInterfaceImplementation(
        types: Map<String, TypeDefinition>,
        owner: String,
        fields: List<FieldDefinition>,
        interfaceName: String,
    ) {
        // requireInterface ran first, so this is always a defined interface.
        val iface = types[interfaceName] as InterfaceTypeDefinition
        for (interfaceField in iface.fields) {
            val implemented = fields.firstOrNull { it.name == interfaceField.name }
                ?: throw SchemaException("Type '$owner' does not implement field '${interfaceField.name}' required by interface '$interfaceName'")
            if (!isValidImplementationType(types, implemented.type, interfaceField.type)) {
                throw SchemaException(
                    "Field '$owner.${interfaceField.name}' type is not compatible with interface '$interfaceName'",
                )
            }
            for (interfaceArg in interfaceField.arguments) {
                val implementedArg = implemented.arguments.firstOrNull { it.name == interfaceArg.name }
                    ?: throw SchemaException("Field '$owner.${interfaceField.name}' is missing argument '${interfaceArg.name}' required by interface '$interfaceName'")
                if (typeString(implementedArg.type) != typeString(interfaceArg.type)) {
                    throw SchemaException("Argument '$owner.${interfaceField.name}.${interfaceArg.name}' type differs from interface '$interfaceName'")
                }
            }
            implemented.arguments
                .filter { extra -> interfaceField.arguments.none { it.name == extra.name } }
                .firstOrNull { it.type is NonNullType && it.defaultValue == null }
                ?.let { throw SchemaException("Field '$owner.${interfaceField.name}' adds required argument '${it.name}' not present on interface '$interfaceName'") }
        }
    }

    /** Output covariance: the implementing type must equal the interface field type or be a valid subtype, allowing non-null to satisfy a nullable position. */
    private fun isValidImplementationType(types: Map<String, TypeDefinition>, impl: Type, iface: Type): Boolean {
        if (iface !is NonNullType && impl is NonNullType) return isValidImplementationType(types, impl.type, iface)
        return when (iface) {
            is NonNullType -> impl is NonNullType && isValidImplementationType(types, impl.type, iface.type)
            is ListType -> impl is ListType && isValidImplementationType(types, impl.type, iface.type)
            is NamedType -> impl is NamedType && (impl.name == iface.name || isSubtype(types, impl.name, iface.name))
        }
    }

    private fun isSubtype(types: Map<String, TypeDefinition>, sub: String, sup: String): Boolean = when (val supType = types[sup]) {
        is InterfaceTypeDefinition -> when (val subType = types[sub]) {
            is ObjectTypeDefinition -> subType.interfaces.any { it.name == sup }
            is InterfaceTypeDefinition -> subType.interfaces.any { it.name == sup }
            else -> false
        }
        is UnionTypeDefinition -> supType.types.any { it.name == sub }
        else -> false
    }

    private fun typeString(type: Type): String = when (type) {
        is NamedType -> type.name
        is ListType -> "[${typeString(type.type)}]"
        is NonNullType -> "${typeString(type.type)}!"
    }

    // ---- default value coercion ----

    private fun requireValidDefault(types: Map<String, TypeDefinition>, value: Value?, type: Type, where: String) {
        if (value == null) return
        coercionProblem(types, value, type)?.let { throw SchemaException("Default value for $where is invalid ($it)") }
    }

    private fun coercionProblem(types: Map<String, TypeDefinition>, value: Value, type: Type): String? = when (type) {
        is NonNullType -> if (value is NullValue) "expected a non-null value" else coercionProblem(types, value, type.type)
        is ListType -> when (value) {
            is NullValue -> null
            is ListValue -> value.values.firstNotNullOfOrNull { coercionProblem(types, it, type.type) }
            else -> coercionProblem(types, value, type.type) // a single value coerces into a list
        }
        is NamedType -> namedCoercionProblem(types, value, type.name)
    }

    private fun namedCoercionProblem(types: Map<String, TypeDefinition>, value: Value, typeName: String): String? {
        // The parser rejects variables in const/default position, so a default value is always a literal here.
        if (value is NullValue) return null
        return when (typeName) {
            "Int" -> if (value is IntValue) null else "expected an Int"
            "Float" -> when {
                value is IntValue -> null
                value is FloatValue -> if (value.value.toDouble().isFinite()) null else "not a finite Float"
                else -> "expected a Float"
            }
            "String" -> if (value is StringValue) null else "expected a String"
            "Boolean" -> if (value is BooleanValue) null else "expected a Boolean"
            "ID" -> if (value is StringValue || value is IntValue) null else "expected an ID"
            else -> when (val definition = types[typeName]) {
                is EnumTypeDefinition ->
                    if (value is EnumValue && definition.values.any { it.name == value.value }) null else "not a valid '$typeName' enum value"
                is InputObjectTypeDefinition -> inputObjectCoercionProblem(types, value, definition)
                // A custom scalar's literal cannot be validated structurally; any non-input type would already have
                // been rejected by requireInputType, so it falls here too and is treated as not-validatable.
                else -> null
            }
        }
    }

    private fun inputObjectCoercionProblem(types: Map<String, TypeDefinition>, value: Value, definition: InputObjectTypeDefinition): String? {
        if (value !is ObjectValue) return "expected input object '${definition.name}'"
        for (f in value.fields) {
            val fieldDef = definition.fields.firstOrNull { it.name == f.name }
                ?: return "unknown field '${f.name}' on '${definition.name}'"
            coercionProblem(types, f.value, fieldDef.type)?.let { return "field '${f.name}': $it" }
        }
        definition.fields.firstOrNull { it.type is NonNullType && it.defaultValue == null && value.fields.none { v -> v.name == it.name } }
            ?.let { return "missing required field '${it.name}'" }
        return null
    }

    // ---- shared helpers ----

    private fun requireUnique(names: List<String>, what: String, scope: () -> String) {
        val seen = mutableSetOf<String>()
        names.firstOrNull { !seen.add(it) }?.let { throw SchemaException("$what '$it' is declared more than once on ${scope()}") }
    }

    private fun requireUnreserved(name: String, what: String) {
        if (name.startsWith("__")) throw SchemaException("$what uses the reserved '__' prefix")
    }

    private fun requireObject(types: Map<String, TypeDefinition>, name: String, where: String) {
        when (types[name]) {
            null -> throw SchemaException("Unknown type '$name' referenced by $where")
            is ObjectTypeDefinition -> Unit
            else -> throw SchemaException("$where must be an object type, but '$name' is not")
        }
    }

    private fun requireInterface(types: Map<String, TypeDefinition>, name: String, where: String) {
        when (types[name]) {
            null -> throw SchemaException("Unknown interface '$name' referenced by $where")
            is InterfaceTypeDefinition -> Unit
            else -> throw SchemaException("$where implements '$name', which is not an interface")
        }
    }

    private fun requireOutputType(types: Map<String, TypeDefinition>, name: String, where: String) {
        when (types[name]) {
            null -> throw SchemaException("Unknown type '$name' referenced by $where")
            is ScalarTypeDefinition, is EnumTypeDefinition, is ObjectTypeDefinition,
            is InterfaceTypeDefinition, is UnionTypeDefinition,
            -> Unit
            else -> throw SchemaException("$where must be an output type, but '$name' is an input type")
        }
    }

    private fun requireInputType(types: Map<String, TypeDefinition>, name: String, where: String) {
        when (types[name]) {
            null -> throw SchemaException("Unknown type '$name' referenced by $where")
            is ScalarTypeDefinition, is EnumTypeDefinition, is InputObjectTypeDefinition -> Unit
            else -> throw SchemaException("$where must be an input type, but '$name' is an output type")
        }
    }
}
