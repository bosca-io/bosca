package bosca.configuration.model

import bosca.security.model.PermissionInput
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ConfigurationInput(
    val key: String,
    val description: String,
    @Contextual
    val value: JsonElement,
    val public: Boolean,
    /** Omitted permissions retain an existing entry's ACL; an empty list explicitly clears it. */
    val permissions: List<PermissionInput>? = null
)