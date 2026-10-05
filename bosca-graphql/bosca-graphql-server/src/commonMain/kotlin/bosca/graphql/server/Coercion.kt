package bosca.graphql.server

import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.EnumValue
import bosca.graphql.language.Field
import bosca.graphql.language.InputObjectTypeDefinition
import bosca.graphql.language.ListType
import bosca.graphql.language.ListValue
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.SourceLocation
import bosca.graphql.language.Type
import bosca.graphql.language.Value
import bosca.graphql.language.Variable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Coerces an operation's request variables (JSON) and a field's argument literals (AST) into internal values
 * against an [ExecutableSchema]. Two recursers over one structure (NonNull / List / scalar / enum /
 * input object): variables bottom out at [Coercing.parseValue] (JSON), arguments at [Coercing.parseLiteral] (AST,
 * resolving variable references). Defaults are applied, null-vs-absent is distinguished, and failures throw
 * [CoercionException] with the path + location.
 */
class Coercion(private val executable: ExecutableSchema) {

    /** Coerce all of [operation]'s variables from [rawVariables], applying defaults and rejecting missing required ones. */
    fun coerceVariables(operation: OperationDefinition, rawVariables: Map<String, Any?>): CoercedVariables {
        val result = mutableMapOf<String, Any?>()
        for (definition in operation.variableDefinitions) {
            val name = definition.variable.name
            val path = listOf<Any>(name)
            val default = definition.defaultValue
            when {
                rawVariables.containsKey(name) ->
                    result[name] = coerceInput(definition.type, rawVariables.getValue(name), path, definition.location)
                default != null ->
                    result[name] = coerceLiteral(definition.type, default, CoercedVariables.EMPTY, path, definition.location)
                definition.type is NonNullType ->
                    throw CoercionException("Variable '\$$name' of required type is not provided", path, definition.location)
                // else: nullable + no default → absent (omitted)
            }
        }
        return CoercedVariables(result)
    }

    /** Coerce [field]'s arguments (its parent is [parentType]) into a map of internal values, resolving [variables]. */
    fun coerceArguments(parentType: String, field: Field, variables: CoercedVariables): Map<String, Any?> {
        val fieldDef = executable.schema.field(parentType, field.name) ?: return emptyMap()
        if (fieldDef.arguments.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Any?>()
        for (argument in fieldDef.arguments) {
            val provided = field.arguments.firstOrNull { it.name == argument.name }
            val path = listOf<Any>(argument.name)
            val default = argument.defaultValue
            when {
                provided != null ->
                    result[argument.name] = coerceLiteral(argument.type, provided.value, variables, path, provided.location)
                default != null ->
                    result[argument.name] = coerceLiteral(argument.type, default, variables, path, field.location)
                argument.type is NonNullType ->
                    throw CoercionException("Required argument '${argument.name}' is not provided", path, field.location)
            }
        }
        return result
    }

    // ---- variable coercion ----

    private fun coerceInput(type: Type, input: Any?, path: List<Any>, location: SourceLocation?): Any? = when (type) {
        is NonNullType -> if (input == null || input is JsonNull) fail("must not be null", path, location) else coerceInput(type.type, input, path, location)
        is ListType -> when (input) {
            null, is JsonNull -> null
            is JsonArray -> input.mapIndexed { index, element -> coerceInput(type.type, element, path + index, location) }
            is List<*> -> input.mapIndexed { index, element -> coerceInput(type.type, element, path + index, location) }
            else -> listOf(coerceInput(type.type, input, path, location)) // single value coerces into a list
        }
        is NamedType -> coerceNamedInput(type.name, input, path, location)
    }

    private fun coerceNamedInput(typeName: String, input: Any?, path: List<Any>, location: SourceLocation?): Any? {
        if (input == null || input is JsonNull) return null
        executable.coercing(typeName)?.let { coercing ->
            return runCoercing(typeName, path, location) { coercing.parseValue(input) }
        }
        return when (val type = executable.schema.type(typeName)) {
            is EnumTypeDefinition -> {
                val primitive = input as? JsonPrimitive
                val name = when {
                    primitive != null && primitive.isString -> primitive.content
                    input is String -> input
                    else -> null
                }
                if (name == null || type.values.none { it.name == name }) fail("is not a valid '$typeName' value", path, location) else name
            }
            is InputObjectTypeDefinition -> {
                val obj = input as? Map<*, *> ?: fail("expected an input object '$typeName'", path, location)
                obj.keys.filterIsInstance<String>().firstOrNull { key -> type.fields.none { it.name == key } }
                    ?.let { fail("unknown field '$it' on input object '$typeName'", path + it, location) }
                val result = mutableMapOf<String, Any?>()
                for (fieldDef in type.fields) {
                    val default = fieldDef.defaultValue
                    when {
                        obj.containsKey(fieldDef.name) -> result[fieldDef.name] = coerceInput(fieldDef.type, obj[fieldDef.name], path + fieldDef.name, location)
                        default != null -> result[fieldDef.name] = coerceLiteral(fieldDef.type, default, CoercedVariables.EMPTY, path + fieldDef.name, location)
                        fieldDef.type is NonNullType -> fail("missing required field '${fieldDef.name}' on '$typeName'", path + fieldDef.name, location)
                    }
                }
                checkOneOf(typeName, type, result, path, location)
                result
            }
            else -> fail("'$typeName' is not an input type", path, location)
        }
    }

    // ---- argument (AST) coercion ----

    private fun coerceLiteral(type: Type, value: Value, variables: CoercedVariables, path: List<Any>, location: SourceLocation?): Any? {
        if (value is Variable) return resolveVariable(type, value, variables, path, location)
        return when (type) {
            is NonNullType -> if (value is NullValue) fail("must not be null", path, location) else coerceLiteral(type.type, value, variables, path, location)
            is ListType -> when (value) {
                is NullValue -> null
                is ListValue -> value.values.mapIndexed { index, element -> coerceLiteral(type.type, element, variables, path + index, location) }
                else -> listOf(coerceLiteral(type.type, value, variables, path, location))
            }
            is NamedType -> coerceNamedLiteral(type.name, value, variables, path, location)
        }
    }

    private fun resolveVariable(type: Type, variable: Variable, variables: CoercedVariables, path: List<Any>, location: SourceLocation?): Any? {
        if (variable.name !in variables.values) {
            return if (type is NonNullType) fail("variable '\$${variable.name}' is not provided", path, location) else null
        }
        val value = variables.values[variable.name]
        if (value == null && type is NonNullType) fail("variable '\$${variable.name}' is null", path, location)
        return value
    }

    private fun coerceNamedLiteral(typeName: String, value: Value, variables: CoercedVariables, path: List<Any>, location: SourceLocation?): Any? {
        if (value is NullValue) return null
        executable.coercing(typeName)?.let { coercing ->
            return runCoercing(typeName, path, location) { coercing.parseLiteral(value) }
        }
        return when (val type = executable.schema.type(typeName)) {
            is EnumTypeDefinition -> {
                val name = (value as? EnumValue)?.value
                if (name == null || type.values.none { it.name == name }) fail("is not a valid '$typeName' value", path, location) else name
            }
            is InputObjectTypeDefinition -> {
                val obj = value as? ObjectValue ?: fail("expected an input object '$typeName'", path, location)
                obj.fields.firstOrNull { f -> type.fields.none { it.name == f.name } }
                    ?.let { fail("unknown field '${it.name}' on input object '$typeName'", path + it.name, location) }
                val byName = obj.fields.associate { it.name to it.value }
                val result = mutableMapOf<String, Any?>()
                for (fieldDef in type.fields) {
                    val default = fieldDef.defaultValue
                    when {
                        fieldDef.name in byName -> result[fieldDef.name] = coerceLiteral(fieldDef.type, byName.getValue(fieldDef.name), variables, path + fieldDef.name, location)
                        default != null -> result[fieldDef.name] = coerceLiteral(fieldDef.type, default, variables, path + fieldDef.name, location)
                        fieldDef.type is NonNullType -> fail("missing required field '${fieldDef.name}' on '$typeName'", path + fieldDef.name, location)
                    }
                }
                checkOneOf(typeName, type, result, path, location)
                result
            }
            else -> fail("'$typeName' is not an input type", path, location)
        }
    }

    // ---- shared ----

    /** A `@oneOf` input object must be given exactly one field, with a non-null value (§ OneOf Input Objects). */
    private fun checkOneOf(typeName: String, type: InputObjectTypeDefinition, result: Map<String, Any?>, path: List<Any>, location: SourceLocation?) {
        if (type.directives.none { it.name == "oneOf" }) return
        if (result.size != 1 || result.values.first() == null) {
            fail("OneOf input object '$typeName' must specify exactly one non-null field", path, location)
        }
    }

    private inline fun runCoercing(typeName: String, path: List<Any>, location: SourceLocation?, block: () -> Any?): Any? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoercingException) {
            throw CoercionException(e.reason, path, location)
        } catch (_: Exception) {
            throw CoercionException("Invalid value for scalar '$typeName'", path, location)
        }

    private fun fail(message: String, path: List<Any>, location: SourceLocation?): Nothing =
        throw CoercionException(message, path, location)
}
