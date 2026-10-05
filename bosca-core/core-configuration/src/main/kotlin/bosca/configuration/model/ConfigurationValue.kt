package bosca.configuration.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class ConfigurationValue(
    @Contextual
    @ColumnName("configuration_id")
    val configurationId: UUID = UUID.NIL,
    val value: ByteArray? = null,
    val nonce: ByteArray,
)