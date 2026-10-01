package bosca.content.ordering

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import kotlinx.serialization.Serializable

/**
 * Defines a single ordering rule for sorting content items. An ordering rule specifies
 * which attribute field to sort by, where that attribute is located, the sort direction,
 * an optional JSON path for nested attribute access, and the attribute's data type for
 * proper comparison semantics.
 */
interface IOrdering {

    /** The name of the attribute field to sort by, or null if unspecified. */
    val field: String?

    /** The location of the attribute (e.g., item attributes vs. system attributes), or null if unspecified. */
    val location: AttributeLocation?

    /** The sort direction (ascending or descending), or null if unspecified. */
    val order: Order?

    /** An optional JSON path segments list for accessing nested attribute values. */
    val path: List<String>?

    /** The data type of the attribute, used for type-appropriate comparison, or null if unspecified. */
    val type: AttributeType?
}

@Serializable
data class Ordering(
    override val field: String? = null,
    @Serializable(with = AttributeLocationSerializer::class)
    override val location: AttributeLocation? = null,
    @Serializable(with = OrderSerializer::class)
    override val order: Order? = null,
    override val path: List<String>? = null,
    @Serializable(with = AttributeTypeLocationSerializer::class)
    override val type: AttributeType? = null,
): IOrdering {

    override fun toString(): String = buildString {
        append(field)
        append(location)
        append(order)
        path?.forEach { append(it) }
        append(type)
    }
}