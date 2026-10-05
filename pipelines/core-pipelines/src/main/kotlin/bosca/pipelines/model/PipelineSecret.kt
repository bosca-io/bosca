package bosca.pipelines.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A named node secret: an API key / token a node references by [name] and
 * resolves at execution. [encryptedValue] is the AES/GCM ciphertext at rest — it is **internal** to the
 * secret service and is never offered via GraphQL (the `PipelineSecret` GraphQL type projects only the
 * name + timestamps). The plaintext exists only transiently when the service decrypts for execution.
 */
@Serializable
data class PipelineSecret(
    val name: String,
    @ColumnName("encrypted_value")
    val encryptedValue: String = "",
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
)
