package bosca.devices.graphql

import bosca.devices.model.Device
import bosca.devices.model.PlatformType
import bosca.devices.model.PushToken
import bosca.devices.service.DeviceService
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves fields on the Device GraphQL type, including nested push token loading.
 */
@TypeController
class DeviceController(
    private val deviceService: DeviceService
) : GraphQLController<Device> {

    @Field
    fun id(device: Device): UUID = device.id

    @Field
    fun platform(device: Device): PlatformType = device.platform

    @Field
    fun created(device: Device): OffsetDateTime = device.created

    @Field
    fun modified(device: Device): OffsetDateTime = device.modified

    @Field
    fun lastCheckIn(device: Device): OffsetDateTime = device.lastCheckIn

    @Field
    fun installationId(device: Device): String? = device.installationId

    @Field
    suspend fun pushTokens(batch: Batch<UUID, List<PushToken>>) {
        deviceService.addPushTokensToBatch(batch)
    }
}
