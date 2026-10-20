package bosca.backup

import bosca.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/** Uses the platform timestamp format for persisted destination observations in integration tests. */
internal val backupTestJson = Json {
    serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) }
}
