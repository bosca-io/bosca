package bosca.cli.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The introspection fields needed for a permission-checked schema search. */
@Serializable
internal data class GraphQLSchemaSearch(@SerialName("__schema") val schema: Schema) {
    @Serializable
    data class Schema(val types: List<Type>)

    @Serializable
    data class Type(val name: String, val description: String? = null,
                    val fields: List<Field>? = null, val inputFields: List<Field>? = null)

    @Serializable
    data class Field(val name: String, val description: String? = null)
}
