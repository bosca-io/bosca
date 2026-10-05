package bosca.graphql.validation

import bosca.graphql.language.Argument
import bosca.graphql.language.BooleanValue
import bosca.graphql.language.Directive
import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumValue
import bosca.graphql.language.Field
import bosca.graphql.language.FloatValue
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.InputValueDefinition
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.ScalarTypeDefinition
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.SourceLocation
import bosca.graphql.language.StringValue
import bosca.graphql.language.Type
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.Value
import bosca.graphql.language.Variable
import bosca.graphql.language.VariableDefinition
import bosca.graphql.language.preOrder
import bosca.graphql.printer.GraphQLPrinter
import bosca.graphql.schema.GraphQLSchema
import bosca.graphql.schema.underlyingTypeName

/**
 * Validates executable definitions (operations + fragments) against a [GraphQLSchema], covering the spec's
 * executable rule set: field existence, leaf/composite selection sets, argument names + **values** +
 * required arguments, unique names (operations, fragments, variables, arguments, input-object fields),
 * variable usage (defined ⇔ used, variables are input types), fragment spread targets + cycles + unused
 * fragments + spread-possible (type overlap), and directive location/uniqueness/arguments.
 *
 * Returns all problems found (empty == valid); [validateOrThrow] raises on the first batch.
 */
class OperationValidator(private val schema: GraphQLSchema) {

    fun validate(document: Document): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val operations = document.definitions.filterIsInstance<OperationDefinition>()
        val fragments = document.definitions.filterIsInstance<FragmentDefinition>()
        val fragmentsByName = fragments.associateBy { it.name }
        val spreadsByFragment = fragments.associate { it.name to directSpreadNames(it.selectionSet) }

        // §5.1.1: a document with executable definitions must not also contain type-system definitions.
        for (definition in document.definitions) {
            if (definition !is OperationDefinition && definition !is FragmentDefinition) {
                errors += ValidationError(
                    "A document with executable definitions must not contain type-system definitions",
                    definition.location,
                )
            }
        }

        validateUniqueOperationNames(operations, errors)
        validateLoneAnonymousOperation(operations, errors)
        validateUniqueFragmentNames(fragments, errors)
        val cyclicFragments = validateFragmentCycles(spreadsByFragment, fragmentsByName.keys, errors)
        validateUnusedFragments(operations, spreadsByFragment, fragmentsByName.keys, errors)

        for (operation in operations) {
            val rootType = schema.rootType(operation.operation)
            if (rootType == null) {
                errors += ValidationError(
                    "Schema does not define a ${operation.operation.name.lowercase()} root type",
                    operation.location,
                )
                continue
            }
            val variableTypes = operation.variableDefinitions.associate { it.variable.name to it.type }
            validateVariableDefinitions(operation, errors)
            operation.variableDefinitions.forEach { validateDirectives(it.directives, "VARIABLE_DEFINITION", variableTypes, errors) }
            validateDirectives(operation.directives, operation.operation.name, variableTypes, errors)
            validateSelectionSet(operation.selectionSet, rootType.name, fragmentsByName, variableTypes, errors)
            validateVariableUsage(operation, spreadsByFragment, fragmentsByName, errors)
            validateVariableUsagesAllowed(operation, rootType.name, fragmentsByName, errors)
            // The merge check expands fragments by nesting level; a fragment cycle (already reported) would make
            // that expansion non-terminating, so skip it for a document that has one.
            if (cyclicFragments.isEmpty()) {
                validateFieldsCanMerge(operation.selectionSet, rootType.name, fragmentsByName, errors)
            }
            if (operation.operation == OperationType.SUBSCRIPTION) {
                validateSubscriptionRootField(operation, fragmentsByName, errors)
            }
        }

        for (fragment in fragments) {
            val onType = fragment.typeCondition.name
            validateDirectives(fragment.directives, "FRAGMENT_DEFINITION", emptyMap(), errors)
            if (schema.type(onType) == null) {
                errors += ValidationError(
                    "Fragment '${fragment.name}' is defined on unknown type '$onType'",
                    fragment.typeCondition.location,
                )
            } else if (!isCompositeType(onType)) {
                errors += ValidationError(
                    "Fragment '${fragment.name}' type condition '$onType' is not a composite type (object, interface, or union)",
                    fragment.typeCondition.location,
                )
            } else {
                validateSelectionSet(fragment.selectionSet, onType, fragmentsByName, emptyMap(), errors)
            }
        }
        return errors
    }

    fun validateOrThrow(document: Document) {
        val errors = validate(document)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "GraphQL document is invalid:\n" + errors.joinToString("\n") { "  - ${it.message}" },
            )
        }
    }

    // ---- document-level uniqueness ----

    private fun validateUniqueOperationNames(operations: List<OperationDefinition>, errors: MutableList<ValidationError>) {
        val seen = mutableSetOf<String>()
        for (operation in operations) {
            val name = operation.name ?: continue
            if (!seen.add(name)) errors += ValidationError("Duplicate operation name '$name'", operation.location)
        }
    }

    private fun validateLoneAnonymousOperation(operations: List<OperationDefinition>, errors: MutableList<ValidationError>) {
        if (operations.size > 1 && operations.any { it.name == null }) {
            errors += ValidationError(
                "An anonymous operation must be the only operation in the document",
                operations.first { it.name == null }.location,
            )
        }
    }

    private fun validateUniqueFragmentNames(fragments: List<FragmentDefinition>, errors: MutableList<ValidationError>) {
        val seen = mutableSetOf<String>()
        for (fragment in fragments) {
            if (!seen.add(fragment.name)) errors += ValidationError("Duplicate fragment name '${fragment.name}'", fragment.location)
        }
    }

    // ---- fragment graph: cycles + unused ----

    private fun directSpreadNames(selectionSet: SelectionSet): List<String> =
        selectionSet.preOrder().filterIsInstance<FragmentSpread>().map { it.name }.distinct().toList()

    /** Reports fragment-spread cycles and returns the set of fragments involved in one. */
    private fun validateFragmentCycles(
        spreadsByFragment: Map<String, List<String>>,
        defined: Set<String>,
        errors: MutableList<ValidationError>,
    ): Set<String> {
        val onStack = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        val reported = mutableSetOf<String>()

        fun visit(name: String) {
            onStack += name
            for (next in spreadsByFragment.getValue(name)) {
                if (next !in defined) continue
                if (next in onStack) {
                    if (reported.add(next)) errors += ValidationError("Fragment '$next' spreads itself (cycle)", null)
                } else if (next !in done) {
                    visit(next)
                }
            }
            onStack -= name
            done += name
        }

        defined.forEach { if (it !in done) visit(it) }
        return reported
    }

    private fun validateUnusedFragments(
        operations: List<OperationDefinition>,
        spreadsByFragment: Map<String, List<String>>,
        defined: Set<String>,
        errors: MutableList<ValidationError>,
    ) {
        val reachable = mutableSetOf<String>()
        fun mark(name: String) {
            if (name in defined && reachable.add(name)) spreadsByFragment.getValue(name).forEach(::mark)
        }
        operations.forEach { op -> directSpreadNames(op.selectionSet).forEach(::mark) }
        defined.filter { it !in reachable }.forEach {
            errors += ValidationError("Fragment '$it' is never used", null)
        }
    }

    // ---- variables ----

    private fun validateVariableDefinitions(operation: OperationDefinition, errors: MutableList<ValidationError>) {
        val seen = mutableSetOf<String>()
        for (definition in operation.variableDefinitions) {
            val name = definition.variable.name
            if (!seen.add(name)) errors += ValidationError("Duplicate variable '\$$name'", definition.location)
            val typeName = underlyingTypeName(definition.type)
            if (!schema.isInputType(typeName)) {
                errors += ValidationError("Variable '\$$name' has non-input type '$typeName'", definition.location)
            }
        }
    }

    private fun validateVariableUsage(
        operation: OperationDefinition,
        spreadsByFragment: Map<String, List<String>>,
        fragmentsByName: Map<String, FragmentDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        val defined = operation.variableDefinitions.map { it.variable.name }.toSet()

        val reachable = mutableSetOf<String>()
        fun mark(name: String) {
            if (name in fragmentsByName && reachable.add(name)) spreadsByFragment.getValue(name).forEach(::mark)
        }
        directSpreadNames(operation.selectionSet).forEach(::mark)

        val used = buildSet {
            addAll(usedVariableNames(operation.selectionSet))
            operation.directives.forEach { addAll(it.preOrder().filterIsInstance<Variable>().map { v -> v.name }) }
            // (variable-definition directives take const arguments only — the parser forbids variables there)
            reachable.forEach { name ->
                val fragment = fragmentsByName.getValue(name)
                addAll(usedVariableNames(fragment.selectionSet))
                fragment.directives.forEach { addAll(it.preOrder().filterIsInstance<Variable>().map { v -> v.name }) }
            }
        }

        (used - defined).forEach { errors += ValidationError("Variable '\$$it' is used but not defined", operation.location) }
        (defined - used).forEach { errors += ValidationError("Variable '\$$it' is defined but never used", operation.location) }
    }

    private fun usedVariableNames(selectionSet: SelectionSet): Set<String> =
        selectionSet.preOrder().filterIsInstance<Variable>().map { it.name }.toSet()

    // ---- selections ----

    private fun validateSelectionSet(
        selectionSet: SelectionSet,
        typeName: String,
        fragments: Map<String, FragmentDefinition>,
        variableTypes: Map<String, Type>,
        errors: MutableList<ValidationError>,
    ) {
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> validateField(selection, typeName, fragments, variableTypes, errors)

                is FragmentSpread -> {
                    validateDirectives(selection.directives, "FRAGMENT_SPREAD", variableTypes, errors)
                    val fragment = fragments[selection.name]
                    if (fragment == null) {
                        errors += ValidationError("Unknown fragment '...${selection.name}'", selection.location)
                    } else if (!typesOverlap(typeName, fragment.typeCondition.name)) {
                        errors += ValidationError(
                            "Fragment '...${selection.name}' on '${fragment.typeCondition.name}' cannot be spread on '$typeName'",
                            selection.location,
                        )
                    }
                }

                is InlineFragment -> {
                    validateDirectives(selection.directives, "INLINE_FRAGMENT", variableTypes, errors)
                    val typeCondition = selection.typeCondition
                    val onType = if (typeCondition != null) typeCondition.name else typeName
                    if (schema.type(onType) == null) {
                        errors += ValidationError("Inline fragment on unknown type '$onType'", selection.location)
                    } else if (!isCompositeType(onType)) {
                        errors += ValidationError("Inline fragment type condition '$onType' is not a composite type (object, interface, or union)", selection.location)
                    } else {
                        if (selection.typeCondition != null && !typesOverlap(typeName, onType)) {
                            errors += ValidationError("Inline fragment on '$onType' cannot be spread on '$typeName'", selection.location)
                        }
                        validateSelectionSet(selection.selectionSet, onType, fragments, variableTypes, errors)
                    }
                }
            }
        }
    }

    private fun validateField(
        field: Field,
        typeName: String,
        fragments: Map<String, FragmentDefinition>,
        variableTypes: Map<String, Type>,
        errors: MutableList<ValidationError>,
    ) {
        validateDirectives(field.directives, "FIELD", variableTypes, errors)

        // `__typename` is a meta-field available on every composite type; it is always a String leaf.
        if (field.name == "__typename") {
            if (field.selectionSet != null) {
                errors += ValidationError("Meta-field '__typename' must not have a selection set", field.location)
            }
            return
        }

        val fieldDef = schema.field(typeName, field.name)
        if (fieldDef == null) {
            errors += ValidationError("Field '${field.name}' does not exist on type '$typeName'", field.location)
            return
        }

        validateArguments(field.arguments, fieldDef.arguments, "field '$typeName.${field.name}'", variableTypes, errors)

        val fieldTypeName = underlyingTypeName(fieldDef.type)
        val isComposite = schema.fields(fieldTypeName) != null || schema.type(fieldTypeName) is UnionTypeDefinition
        if (isComposite) {
            if (field.selectionSet == null) {
                errors += ValidationError(
                    "Field '$typeName.${field.name}' returns composite type '$fieldTypeName' and must have a selection set",
                    field.location,
                )
            } else {
                validateSelectionSet(field.selectionSet, fieldTypeName, fragments, variableTypes, errors)
            }
        } else if (field.selectionSet != null) {
            errors += ValidationError(
                "Field '$typeName.${field.name}' returns leaf type '$fieldTypeName' and must not have a selection set",
                field.location,
            )
        }
    }

    // ---- arguments + directives ----

    private fun validateArguments(
        provided: List<Argument>,
        defined: List<InputValueDefinition>,
        owner: String,
        variableTypes: Map<String, Type>,
        errors: MutableList<ValidationError>,
    ) {
        val seen = mutableSetOf<String>()
        for (argument in provided) {
            if (!seen.add(argument.name)) {
                errors += ValidationError("Duplicate argument '${argument.name}' on $owner", argument.location)
            }
            val argumentDef = defined.firstOrNull { it.name == argument.name }
            if (argumentDef == null) {
                errors += ValidationError("Unknown argument '${argument.name}' on $owner", argument.location)
            } else {
                checkValue(argument.value, argumentDef.type, variableTypes, "argument '${argument.name}' on $owner", errors, argument.location)
            }
        }
        for (argumentDef in defined) {
            if (argumentDef.type is NonNullType && argumentDef.defaultValue == null && provided.none { it.name == argumentDef.name }) {
                errors += ValidationError("Missing required argument '${argumentDef.name}' on $owner", null)
            }
        }
    }

    private fun validateDirectives(
        directives: List<Directive>,
        location: String,
        variableTypes: Map<String, Type>,
        errors: MutableList<ValidationError>,
    ) {
        val seenNonRepeatable = mutableSetOf<String>()
        for (directive in directives) {
            val definition = schema.directives[directive.name]
            if (definition == null) {
                errors += ValidationError("Unknown directive '@${directive.name}'", directive.location)
                continue
            }
            if (location !in definition.locations) {
                errors += ValidationError("Directive '@${directive.name}' is not allowed on $location", directive.location)
            }
            if (!definition.repeatable && !seenNonRepeatable.add(directive.name)) {
                errors += ValidationError("Directive '@${directive.name}' is not repeatable", directive.location)
            }
            validateArguments(directive.arguments, definition.arguments, "directive '@${directive.name}'", variableTypes, errors)
        }
    }

    // ---- value type-checking ----

    private fun checkValue(
        value: Value,
        type: Type,
        variableTypes: Map<String, Type>,
        context: String,
        errors: MutableList<ValidationError>,
        location: SourceLocation?,
    ) {
        if (value is Variable) return // presence/usage handled by variable reconciliation
        when (type) {
            is NonNullType -> {
                if (value is NullValue) errors += ValidationError("Expected a non-null value for $context", location)
                else checkValue(value, type.type, variableTypes, context, errors, location)
            }
            is ListType -> when (value) {
                is NullValue -> Unit
                is ListValue -> value.values.forEach { checkValue(it, type.type, variableTypes, context, errors, location) }
                else -> checkValue(value, type.type, variableTypes, context, errors, location) // single value coerces to a list
            }
            is NamedType -> checkNamedValue(value, type.name, variableTypes, context, errors, location)
        }
    }

    private fun checkNamedValue(
        value: Value,
        typeName: String,
        variableTypes: Map<String, Type>,
        context: String,
        errors: MutableList<ValidationError>,
        location: SourceLocation?,
    ) {
        if (value is NullValue) return // null is acceptable for a nullable position
        fun mismatch() = errors.add(ValidationError("Value for $context is not a valid '$typeName'", location))
        when (typeName) {
            "Int" -> if (value !is IntValue) mismatch()
            "Float" -> if (value !is FloatValue && value !is IntValue) mismatch()
            "String" -> if (value !is StringValue) mismatch()
            "Boolean" -> if (value !is BooleanValue) mismatch()
            "ID" -> if (value !is StringValue && value !is IntValue) mismatch()
            else -> when (val definition = schema.type(typeName)) {
                is EnumTypeDefinition ->
                    if (value !is EnumValue || definition.values.none { it.name == value.value }) mismatch()
                is InputObjectTypeDefinition -> checkInputObject(value, definition, variableTypes, errors, location)
                is ScalarTypeDefinition -> Unit // custom scalar: literal cannot be validated structurally
                else -> errors += ValidationError("Type '$typeName' for $context is not an input type", location)
            }
        }
    }

    private fun checkInputObject(
        value: Value,
        definition: InputObjectTypeDefinition,
        variableTypes: Map<String, Type>,
        errors: MutableList<ValidationError>,
        location: SourceLocation?,
    ) {
        if (value !is ObjectValue) {
            errors += ValidationError("Expected an input object '${definition.name}'", location)
            return
        }
        val seen = mutableSetOf<String>()
        for (field in value.fields) {
            if (!seen.add(field.name)) {
                errors += ValidationError("Duplicate input field '${field.name}' on '${definition.name}'", field.location)
            }
            val fieldDef = definition.fields.firstOrNull { it.name == field.name }
            if (fieldDef == null) {
                errors += ValidationError("Unknown input field '${field.name}' on '${definition.name}'", field.location)
            } else {
                checkValue(field.value, fieldDef.type, variableTypes, "input field '${definition.name}.${field.name}'", errors, field.location)
            }
        }
        for (fieldDef in definition.fields) {
            if (fieldDef.type is NonNullType && fieldDef.defaultValue == null && value.fields.none { it.name == fieldDef.name }) {
                errors += ValidationError("Missing required input field '${fieldDef.name}' on '${definition.name}'", location)
            }
        }
        // §5.6: a @oneOf input object literal must specify exactly one field, whose value is not an explicit null.
        if (definition.directives.any { it.name == "oneOf" }) {
            if (value.fields.size != 1) {
                errors += ValidationError("OneOf input object '${definition.name}' must specify exactly one field", location)
            } else if (value.fields.single().value is NullValue) {
                errors += ValidationError("OneOf input object '${definition.name}' field '${value.fields.single().name}' must not be null", location)
            }
        }
    }

    // ---- type overlap (fragment spread possibility) ----

    private fun typesOverlap(parent: String, condition: String): Boolean {
        val parentTypes = concreteTypes(parent)
        val conditionTypes = concreteTypes(condition)
        return parentTypes.any { it in conditionTypes }
    }

    private fun concreteTypes(typeName: String): Set<String> {
        val possible = schema.possibleTypes(typeName)
        return if (possible.isEmpty()) setOf(typeName) else possible.map { it.name }.toSet()
    }

    private fun isCompositeType(typeName: String): Boolean =
        schema.fields(typeName) != null || schema.type(typeName) is UnionTypeDefinition

    // ---- §5.2.3.1 subscription single root field ----

    private fun validateSubscriptionRootField(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        val rootFields = subscriptionRootFields(operation.selectionSet, fragments, mutableSetOf())
        val responseKeys = rootFields.map { it.alias ?: it.name }.distinct()
        val name = operation.name
        val label = if (name != null) "Subscription '$name'" else "Anonymous subscription"
        if (responseKeys.size != 1) {
            errors += ValidationError("$label must select exactly one root field", operation.location)
        } else if (rootFields.any { it.name == "__typename" }) {
            errors += ValidationError("$label must not select '__typename' as its root field", operation.location)
        }
    }

    /** Top-level fields of a subscription's selection set, expanding fragment spreads + inline fragments. */
    private fun subscriptionRootFields(
        selectionSet: SelectionSet,
        fragments: Map<String, FragmentDefinition>,
        visited: MutableSet<String>,
    ): List<Field> = buildList {
        for (selection in selectionSet.selections) when (selection) {
            is Field -> add(selection)
            is InlineFragment -> addAll(subscriptionRootFields(selection.selectionSet, fragments, visited))
            is FragmentSpread -> {
                val fragment = fragments[selection.name]
                if (fragment != null && visited.add(selection.name)) {
                    addAll(subscriptionRootFields(fragment.selectionSet, fragments, visited))
                }
            }
        }
    }

    // ---- §5.8.5 variable usages allowed (variable type compatible with the usage location) ----

    private fun validateVariableUsagesAllowed(
        operation: OperationDefinition,
        rootTypeName: String,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        val varDefs = operation.variableDefinitions.associateBy { it.variable.name }
        if (varDefs.isEmpty()) return
        val visited = mutableSetOf<String>()

        fun walk(selectionSet: SelectionSet, parentType: String) {
            for (selection in selectionSet.selections) when (selection) {
                is Field -> {
                    checkDirectiveVariableUsages(selection.directives, varDefs, errors)
                    val fieldDef = if (selection.name == "__typename") null else schema.field(parentType, selection.name)
                    if (fieldDef != null) {
                        for (argument in selection.arguments) {
                            val argumentDef = fieldDef.arguments.firstOrNull { it.name == argument.name } ?: continue
                            checkVariableUsage(argument.value, argumentDef.type, argumentDef.defaultValue != null, varDefs, errors)
                        }
                        selection.selectionSet?.let { walk(it, underlyingTypeName(fieldDef.type)) }
                    }
                }
                is InlineFragment -> {
                    checkDirectiveVariableUsages(selection.directives, varDefs, errors)
                    val condition = selection.typeCondition
                val onType = if (condition != null) condition.name else parentType
                    if (isCompositeType(onType)) walk(selection.selectionSet, onType)
                }
                is FragmentSpread -> {
                    checkDirectiveVariableUsages(selection.directives, varDefs, errors)
                    val fragment = fragments[selection.name]
                    if (fragment != null && visited.add(selection.name) && isCompositeType(fragment.typeCondition.name)) {
                        walk(fragment.selectionSet, fragment.typeCondition.name)
                    }
                }
            }
        }
        walk(operation.selectionSet, rootTypeName)
    }

    private fun checkDirectiveVariableUsages(
        directives: List<Directive>,
        varDefs: Map<String, VariableDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        for (directive in directives) {
            val definition = schema.directives[directive.name] ?: continue
            for (argument in directive.arguments) {
                val argumentDef = definition.arguments.firstOrNull { it.name == argument.name } ?: continue
                checkVariableUsage(argument.value, argumentDef.type, argumentDef.defaultValue != null, varDefs, errors)
            }
        }
    }

    private fun checkVariableUsage(
        value: Value,
        locationType: Type,
        locationHasDefault: Boolean,
        varDefs: Map<String, VariableDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        when (value) {
            is Variable -> {
                val varDef = varDefs[value.name] ?: return // an undefined-variable use is reported by §5.8.3
                if (!isVariableUsageAllowed(varDef, locationType, locationHasDefault)) {
                    errors += ValidationError(
                        "Variable '\$${value.name}' of type '${typeString(varDef.type)}' cannot be used where '${typeString(locationType)}' is expected",
                        value.location,
                    )
                }
            }
            is ListValue -> {
                val element = elementType(locationType)
                value.values.forEach { checkVariableUsage(it, element, false, varDefs, errors) }
            }
            is ObjectValue -> {
                val inputDef = schema.type(underlyingTypeName(locationType)) as? InputObjectTypeDefinition
                if (inputDef != null) {
                    for (field in value.fields) {
                        val fieldDef = inputDef.fields.firstOrNull { it.name == field.name }
                        if (fieldDef != null) checkVariableUsage(field.value, fieldDef.type, fieldDef.defaultValue != null, varDefs, errors)
                    }
                }
            }
            else -> Unit
        }
    }

    /** IsVariableUsageAllowed (§5.8.5): the variable's declared type must be compatible with the location type. */
    private fun isVariableUsageAllowed(varDef: VariableDefinition, locationType: Type, locationHasDefault: Boolean): Boolean {
        val variableType = varDef.type
        if (locationType is NonNullType && variableType !is NonNullType) {
            val hasNonNullVariableDefault = varDef.defaultValue != null && varDef.defaultValue !is NullValue
            if (!hasNonNullVariableDefault && !locationHasDefault) return false
            return areTypesCompatible(variableType, locationType.type)
        }
        return areTypesCompatible(variableType, locationType)
    }

    private fun areTypesCompatible(variableType: Type, locationType: Type): Boolean {
        if (locationType is NonNullType) {
            if (variableType !is NonNullType) return false
            return areTypesCompatible(variableType.type, locationType.type)
        }
        if (variableType is NonNullType) return areTypesCompatible(variableType.type, locationType)
        if (locationType is ListType) {
            if (variableType !is ListType) return false
            return areTypesCompatible(variableType.type, locationType.type)
        }
        if (variableType is ListType) return false
        return (variableType as NamedType).name == (locationType as NamedType).name
    }

    private fun elementType(type: Type): Type = when (type) {
        is NonNullType -> elementType(type.type)
        is ListType -> type.type
        is NamedType -> type
    }

    private fun typeString(type: Type): String = when (type) {
        is NonNullType -> typeString(type.type) + "!"
        is ListType -> "[" + typeString(type.type) + "]"
        is NamedType -> type.name
    }

    // ---- §5.3.2 field selection merging (FieldsInSetCanMerge / SameResponseShape) ----

    private data class FieldInContext(val field: Field, val parentType: String)

    private fun validateFieldsCanMerge(
        selectionSet: SelectionSet,
        parentType: String,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        val collected = mutableListOf<FieldInContext>()
        collectFieldsForMerge(selectionSet, parentType, fragments, mutableSetOf(), collected)
        fieldsCanMerge(collected, fragments, errors)
    }

    private fun collectFieldsForMerge(
        selectionSet: SelectionSet,
        parentType: String,
        fragments: Map<String, FragmentDefinition>,
        visited: MutableSet<String>,
        out: MutableList<FieldInContext>,
    ) {
        for (selection in selectionSet.selections) when (selection) {
            is Field -> out += FieldInContext(selection, parentType)
            is InlineFragment -> {
                val condition = selection.typeCondition
                val onType = if (condition != null) condition.name else parentType
                if (isCompositeType(onType)) collectFieldsForMerge(selection.selectionSet, onType, fragments, visited, out)
            }
            is FragmentSpread -> {
                val fragment = fragments[selection.name]
                if (fragment != null && visited.add(selection.name) && isCompositeType(fragment.typeCondition.name)) {
                    collectFieldsForMerge(fragment.selectionSet, fragment.typeCondition.name, fragments, visited, out)
                }
            }
        }
    }

    private fun fieldsCanMerge(
        fields: List<FieldInContext>,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        // Scoped per selection-set level: dedups the duplicate reasons within one group, while letting independent
        // conflicts on the same response key in different sub-trees each be reported.
        val reported = mutableSetOf<String>()
        val byResponseName = fields.groupBy { it.field.alias ?: it.field.name }
        for ((responseName, group) in byResponseName) {
            val sameKnownParent = group.map { it.parentType }.distinct().size == 1 &&
                group.all { fieldReturnType(it) != null }
            if (sameKnownParent) {
                checkSameParentGroup(responseName, group, errors, reported)
            } else {
                val argumentSignatures = group.associate { it.field to argumentSignature(it.field) }
                for (i in group.indices) {
                    for (j in i + 1 until group.size) {
                        checkMergePair(responseName, group[i], group[j], errors, reported, argumentSignatures)
                    }
                }
            }
            // Recurse into the combined sub-selections of the whole group so cross-field nested conflicts surface.
            val merged = mutableListOf<FieldInContext>()
            for (fic in group) {
                val sub = fic.field.selectionSet ?: continue
                collectFieldsForMerge(sub, fieldReturnTypeName(fic), fragments, mutableSetOf(), merged)
            }
            if (merged.isNotEmpty()) fieldsCanMerge(merged, fragments, errors)
        }
    }

    /**
     * The overwhelmingly common case has one known parent type. Pairwise comparison is equivalent to grouping by
     * response shape, field name, and argument signature, reducing a repeated-alias attack from O(n²) to O(n).
     */
    private fun checkSameParentGroup(
        responseName: String,
        group: List<FieldInContext>,
        errors: MutableList<ValidationError>,
        reported: MutableSet<String>,
    ) {
        fun report(reason: String, field: Field) {
            if (reported.add("$responseName:$reason")) {
                errors += ValidationError("Fields '$responseName' conflict because $reason; use different aliases", field.location)
            }
        }

        val byShape = group.groupBy { responseShapeKey(fieldReturnType(it)!!) }
        if (byShape.size > 1) {
            report("they return conflicting types", group.first().field)
        }
        for (sameShape in byShape.values) {
            val byFieldName = sameShape.groupBy { it.field.name }
            if (byFieldName.size > 1) {
                val names = byFieldName.keys.iterator()
                val first = names.next()
                val second = names.next()
                report("'$first' and '$second' are different fields", sameShape.first().field)
            }
            for (sameField in byFieldName.values) {
                if (sameField.asSequence().map { argumentSignature(it.field) }.distinct().take(2).count() > 1) {
                    report("they have differing arguments", sameField.first().field)
                }
            }
        }
    }

    private fun checkMergePair(
        responseName: String,
        a: FieldInContext,
        b: FieldInContext,
        errors: MutableList<ValidationError>,
        reported: MutableSet<String>,
        argumentSignatures: Map<Field, Map<String, String>>,
    ) {
        fun report(reason: String) {
            if (reported.add("$responseName:$reason")) {
                errors += ValidationError("Fields '$responseName' conflict because $reason; use different aliases", a.field.location)
            }
        }
        if (!sameResponseShape(a, b)) {
            report("they return conflicting types")
            return
        }
        // Fields that could apply to the same object at runtime must be truly identical.
        if (a.parentType == b.parentType || !isObjectType(a.parentType) || !isObjectType(b.parentType)) {
            if (a.field.name != b.field.name) {
                report("'${a.field.name}' and '${b.field.name}' are different fields")
            } else if (argumentSignatures.getValue(a.field) != argumentSignatures.getValue(b.field)) {
                report("they have differing arguments")
            }
        }
    }

    private fun sameResponseShape(a: FieldInContext, b: FieldInContext): Boolean =
        shapesMatch(fieldReturnType(a), fieldReturnType(b))

    private fun shapesMatch(a: Type?, b: Type?): Boolean {
        // an unknown field is reported by §5.3.1; don't double-report a shape conflict here
        if (a == null) return true
        if (b == null) return true
        if (a is NonNullType) return b is NonNullType && shapesMatch(a.type, b.type)
        if (b is NonNullType) return false
        if (a is ListType) return b is ListType && shapesMatch(a.type, b.type)
        if (b is ListType) return false
        val nameA = (a as NamedType).name
        val nameB = (b as NamedType).name
        if (!isLeafType(nameA) && !isLeafType(nameB)) return true
        return nameA == nameB
    }

    private fun responseShapeKey(type: Type): String = when (type) {
        is NonNullType -> "N${responseShapeKey(type.type)}"
        is ListType -> "L${responseShapeKey(type.type)}"
        is NamedType -> if (isLeafType(type.name)) "S:${type.name}" else "O"
    }

    private fun argumentSignature(field: Field): Map<String, String> =
        field.arguments.associate { it.name to GraphQLPrinter.printCompact(it.value) }

    private fun fieldReturnType(fic: FieldInContext): Type? =
        if (fic.field.name == "__typename") NamedType("String") else schema.field(fic.parentType, fic.field.name)?.type

    private fun fieldReturnTypeName(fic: FieldInContext): String {
        val type = fieldReturnType(fic)
        return if (type != null) underlyingTypeName(type) else fic.parentType
    }

    private fun isObjectType(typeName: String): Boolean = schema.type(typeName) is ObjectTypeDefinition

    private fun isLeafType(typeName: String): Boolean =
        schema.type(typeName) is ScalarTypeDefinition || schema.type(typeName) is EnumTypeDefinition
}
