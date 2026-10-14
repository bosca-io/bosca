package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Mutually exclusive observed-placement segments within an assigned arm. */
@Serializable
data class PlacementIntegrity(
    val expectedOnly: Long = 0, val unexpectedOnly: Long = 0, val both: Long = 0, val neither: Long = 0,
)
