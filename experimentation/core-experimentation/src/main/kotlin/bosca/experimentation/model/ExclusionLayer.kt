package bosca.experimentation.model

import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A mutual exclusion layer that prevents users from being enrolled in
 * multiple conflicting experiments simultaneously.
 *
 * Experiments assigned to the same layer compete for traffic — once a user
 * is bucketed into one experiment within a layer, they are excluded from
 * all other experiments in that layer. This ensures clean, independent
 * measurement by eliminating interaction effects between concurrent tests.
 */
@BatchKey("id")
@Serializable
data class ExclusionLayer(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String = "",
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Input for creating or updating an exclusion layer.
 */
@Serializable
data class ExclusionLayerInput(
    val name: String,
    val description: String? = null
)
