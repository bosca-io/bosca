package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Input for creating or updating a [PersonalizationSignalDefinition].
 */
@Serializable
data class PersonalizationSignalDefinitionInput(
    val key: String,
    val sourceType: PersonalizationSignalSourceType,
    val sourceId: String,
    val expression: String,
    val valueType: PersonalizationSignalValueType,
    val priority: Int = 0,
    val useAsFeature: Boolean = true,
    val useAsCohort: Boolean = false,
    val enabled: Boolean = true,
)
