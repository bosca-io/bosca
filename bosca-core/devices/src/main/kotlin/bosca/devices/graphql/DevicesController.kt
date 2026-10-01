package bosca.devices.graphql

import bosca.devices.model.Device
import bosca.devices.service.DeviceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Handles query operations for retrieving device registrations.
 */
@TypeController
class DevicesController(
    private val deviceService: DeviceService,
    private val groups: GroupEvaluator
) : GraphQLController<Devices> {

    @Field
    suspend fun getById(authentication: AuthenticationContext, id: UUID): Device? {
        val device = deviceService.getById(id) ?: return null
        val principal = authentication.principal() ?: return null
        if (device.principalId != principal.id && !groups.hasAdminGroup(authentication)) return null
        return device
    }

    @Field
    suspend fun devices(authentication: AuthenticationContext, principalId: UUID?): List<Device> {
        val principal = authentication.principal() ?: return emptyList()
        if (principalId != null && principalId != principal.id && !groups.hasAdminGroup(authentication)) return emptyList()
        return deviceService.getByPrincipal(principalId ?: principal.id)
    }
}
