package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsKeyValueEntry
import bosca.nats.admin.service.NatsMonitoringService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Marker object representing the `NatsAdminMutation` GraphQL mutation type.
 * Returned by the root [MutationController] to namespace all NATS admin mutations.
 */
object NatsAdminMutation

/**
 * Handles GraphQL mutation operations for modifying NATS KeyValue store entries
 * and JetStream stream messages. All operations require admin group membership.
 */
@TypeController
class NatsAdminMutationController(
    private val natsMonitoringService: NatsMonitoringService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<NatsAdminMutation> {

    /**
     * Creates or updates a key-value entry in the specified bucket.
     * Returns the entry with its new revision number after the write.
     */
    @Field
    suspend fun putKeyValueEntry(
        authorization: AuthenticationContext,
        bucket: String,
        key: String,
        value: String,
    ): NatsKeyValueEntry {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.putKeyValueEntry(bucket, key, value)
    }

    /**
     * Deletes a key-value entry from the specified bucket by marking it
     * with a DELETE tombstone.
     */
    @Field
    suspend fun deleteKeyValueEntry(
        authorization: AuthenticationContext,
        bucket: String,
        key: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.deleteKeyValueEntry(bucket, key)
    }

    /**
     * Permanently removes all tombstoned entries from a KeyValue store bucket,
     * reclaiming storage while preserving live entries.
     */
    @Field
    suspend fun purgeKeyValueDeletes(
        authorization: AuthenticationContext,
        bucket: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.purgeKeyValueDeletes(bucket)
    }

    /**
     * Purges all messages from a JetStream stream, resetting it to empty
     * while preserving the stream configuration and consumers.
     */
    @Field
    suspend fun purgeStream(
        authorization: AuthenticationContext,
        streamName: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.purgeStream(streamName)
    }

    /**
     * Iterates through all messages in a stream and removes KeyValue tombstones
     * (DEL/PURGE markers), reclaiming storage while preserving live entries.
     * Returns the number of tombstone messages removed.
     */
    @Field
    suspend fun purgeStreamDeletes(
        authorization: AuthenticationContext,
        streamName: String,
    ): Int {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.purgeStreamDeletes(streamName)
    }

    /**
     * Deletes a single message from a JetStream stream by sequence number.
     */
    @Field
    suspend fun deleteStreamMessage(
        authorization: AuthenticationContext,
        streamName: String,
        sequence: Long,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.deleteStreamMessage(streamName, sequence)
    }
}
