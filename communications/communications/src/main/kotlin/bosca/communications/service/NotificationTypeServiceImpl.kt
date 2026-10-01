package bosca.communications.service

import bosca.communications.model.NotificationType
import bosca.communications.repository.NotificationTypeRepository
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class NotificationTypeServiceImpl(
    private val repository: NotificationTypeRepository,
) : NotificationTypeService {

    override suspend fun list(): List<NotificationType> = repository.list()

    override suspend fun get(key: String): NotificationType? = repository.get(key)

    override suspend fun set(
        key: String,
        name: String,
        description: String?,
        optional: Boolean,
        defaultEmailEnabled: Boolean,
        defaultPushEnabled: Boolean,
        displayOrder: Int,
        hidden: Boolean,
    ): NotificationType {
        require(KEY.matches(key)) { "type key must be a lowercase slug, got: $key" }
        require(name.isNotBlank()) { "type name must not be blank" }
        require(optional || (defaultEmailEnabled && defaultPushEnabled)) {
            "non-optional type $key must be enabled by default for email and push"
        }
        val existing = repository.get(key)
        if (existing != null && existing.system) {
            require(existing.optional == optional) {
                "the optional flag of system type ${existing.key} cannot be changed"
            }
        }
        return repository.upsert(
            key,
            name,
            description,
            optional,
            defaultEmailEnabled,
            defaultPushEnabled,
            displayOrder,
            hidden,
        )
    }

    override suspend fun delete(key: String): Boolean {
        val existing = repository.get(key) ?: return false
        require(!existing.system) { "system type $key cannot be deleted" }
        return repository.delete(key) > 0
    }

    companion object {
        private val KEY = Regex("^[a-z][a-z0-9_-]{0,63}$")
    }
}
