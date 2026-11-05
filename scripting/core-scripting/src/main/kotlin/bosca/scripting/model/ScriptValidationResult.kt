package bosca.scripting.model

import kotlinx.serialization.Serializable

/** Source validation outcome; compiled indicates whether a compilation check was attempted. */
@Serializable
data class ScriptValidationResult(val valid: Boolean, val compiled: Boolean, val errors: List<String>)
