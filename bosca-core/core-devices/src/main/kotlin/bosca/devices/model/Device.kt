package bosca.devices.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents a registered client installation and its optional principal association.
 *
 * Devices and their push tokens are installation-owned. [principalId] identifies the currently
 * signed-in principal and is cleared on sign-out without deleting the device or its tokens.
 */
@BatchKey("id")
@Serializable
data class Device(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("principal_id")
    val principalId: UUID? = null,
    val platform: PlatformType,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    @ColumnName("last_check_in")
    val lastCheckIn: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("installation_id")
    val installationId: String? = null
)

/**
 * Input for registering a new device or updating an existing device registration.
 * [installationId] is issued by the Analytics installation endpoint and is required for every
 * device registration.
 */
@Serializable
data class DeviceInput(
    val platform: PlatformType,
    val installationId: String,
)
