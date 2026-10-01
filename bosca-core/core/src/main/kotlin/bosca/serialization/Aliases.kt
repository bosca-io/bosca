package bosca.serialization

import kotlinx.serialization.Serializable

typealias UUID = kotlin.uuid.Uuid
typealias OffsetDateTime = @Serializable(OffsetDateTimeSerializer::class) java.time.OffsetDateTime
typealias LocalDateTime = @Serializable(LocalDateTimeSerializer::class) java.time.LocalDateTime
typealias ZonedDateTime = @Serializable(ZonedDateTimeSerializer::class) java.time.ZonedDateTime