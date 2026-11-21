package bosca.design.model

import kotlinx.serialization.Serializable

/** An application-resolved choice for a field in an existing Forms UI Schema. */
@Serializable
data class SchemaFormOption(val value: String, val label: String, val disabled: Boolean = false)
