package bosca.chat.security

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatObjectType
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/** Enforces the owning object's VIEW permission for object-scoped chat channels. */
class ChatObjectPermissionEvaluator(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionService: CollectionService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
) {

    /** Verifies that [authentication] may open chat for the referenced object. */
    suspend fun verifyAllowed(
        authentication: AuthenticationContext,
        objectType: ChatObjectType,
        objectId: UUID,
    ) {
        when (objectType) {
            ChatObjectType.METADATA -> {
                val metadata = metadataService.getById(objectId)
                    ?: throw NoSuchElementException("metadata object not found")
                metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
            }
            ChatObjectType.COLLECTION -> {
                val collection = collectionService.getById(objectId)
                    ?: throw NoSuchElementException("collection object not found")
                collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.VIEW)
            }
            ChatObjectType.LOCALIZATION_KEY,
            ChatObjectType.CALENDAR_EVENT,
            ChatObjectType.FEATURE_FLAG,
            ChatObjectType.EXPERIMENT -> throw IllegalArgumentException(
                "chat channels for $objectType are not supported until object authorization is available",
            )
        }
    }

    /** Verifies the owning object when [channel] is object-scoped. */
    suspend fun verifyAllowed(authentication: AuthenticationContext, channel: ChatChannel) {
        val objectType = channel.objectType
        val objectId = channel.objectId
        check((objectType == null) == (objectId == null)) { "chat channel has an incomplete object reference" }
        if (objectType != null && objectId != null) {
            verifyAllowed(authentication, objectType, objectId)
        }
    }

    /** Returns whether [authentication] may access [channel]'s owning object. */
    suspend fun isAllowed(authentication: AuthenticationContext, channel: ChatChannel): Boolean {
        val objectType = channel.objectType ?: return channel.objectId == null
        val objectId = channel.objectId ?: return false
        return when (objectType) {
            ChatObjectType.METADATA -> metadataService.getById(objectId)?.let {
                metadataPermissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW)
            } ?: false
            ChatObjectType.COLLECTION -> collectionService.getById(objectId)?.let {
                collectionPermissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW)
            } ?: false
            ChatObjectType.LOCALIZATION_KEY,
            ChatObjectType.CALENDAR_EVENT,
            ChatObjectType.FEATURE_FLAG,
            ChatObjectType.EXPERIMENT -> false
        }
    }

    /**
     * Evaluates multiple identities against [channel]'s owning object while loading the object and
     * its ACL once. Results correspond positionally to [authentications].
     */
    suspend fun isAllowed(
        authentications: List<AuthenticationContext>,
        channel: ChatChannel,
    ): List<Boolean> {
        val objectType = channel.objectType
        val objectId = channel.objectId
        if (objectType == null) {
            return List(authentications.size) { objectId == null }
        }
        if (objectId == null) return List(authentications.size) { false }
        return when (objectType) {
            ChatObjectType.METADATA -> metadataService.getById(objectId)?.let { metadata ->
                metadataPermissionEvaluator.isAllowed(authentications, metadata, PermissionAction.VIEW)
            } ?: List(authentications.size) { false }
            ChatObjectType.COLLECTION -> collectionService.getById(objectId)?.let { collection ->
                collectionPermissionEvaluator.isAllowed(authentications, collection, PermissionAction.VIEW)
            } ?: List(authentications.size) { false }
            ChatObjectType.LOCALIZATION_KEY,
            ChatObjectType.CALENDAR_EVENT,
            ChatObjectType.FEATURE_FLAG,
            ChatObjectType.EXPERIMENT -> List(authentications.size) { false }
        }
    }
}
