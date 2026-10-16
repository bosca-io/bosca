package bosca.bml.library

import kotlinx.serialization.Serializable

/** Authored Kotlin type and default presence; default expressions execute in the compiled producer. */
@Serializable
data class BmlLibraryProp(val name: String, val declaredType: String, val hasDefault: Boolean)
