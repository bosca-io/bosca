package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamConsumerDetail
import bosca.nats.admin.model.JetStreamInfo
import bosca.nats.admin.model.JetStreamStreamDetail
import bosca.nats.admin.model.NatsConnections
import bosca.nats.admin.model.NatsKeyValueEntry
import bosca.nats.admin.model.NatsKeyValueStore
import bosca.nats.admin.model.NatsRoutes
import bosca.nats.admin.model.NatsServerInfo
import bosca.nats.admin.model.NatsStreamMessage
import bosca.nats.admin.model.NatsSubscriptionsInfo
import bosca.nats.admin.service.NatsMonitoringService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Exposes NATS server monitoring data through the GraphQL API, allowing
 * administrators to inspect server health, active connections, JetStream
 * streams and consumers, subscription routing statistics, KeyValue store
 * contents, and stream messages.
 */
@TypeController
class NatsAdminController(
    private val natsMonitoringService: NatsMonitoringService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<NatsAdmin> {

    @Field
    suspend fun serverInfo(authorization: AuthenticationContext): NatsServerInfo {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getServerInfo()
    }

    @Field
    suspend fun clusterRoutes(authorization: AuthenticationContext): NatsRoutes {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getClusterRoutes()
    }

    @Field
    suspend fun connections(
        authorization: AuthenticationContext,
        limit: Int?,
        offset: Int?,
        sortBy: String?,
    ): NatsConnections {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getConnections(limit ?: 100, offset ?: 0, sortBy)
    }

    @Field
    suspend fun jetStreamInfo(authorization: AuthenticationContext): JetStreamInfo {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getJetStreamInfo()
    }

    @Field
    suspend fun jetStreamStreams(authorization: AuthenticationContext): List<JetStreamStreamDetail> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getJetStreamStreams()
    }

    @Field
    suspend fun jetStreamConsumers(
        authorization: AuthenticationContext,
        streamName: String,
    ): List<JetStreamConsumerDetail> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getJetStreamConsumers(streamName)
    }

    @Field
    suspend fun subscriptionsInfo(authorization: AuthenticationContext): NatsSubscriptionsInfo {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getSubscriptionsInfo()
    }

    @Field
    suspend fun keyValueStores(authorization: AuthenticationContext): List<NatsKeyValueStore> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getKeyValueStores()
    }

    @Field
    suspend fun keyValueEntries(
        authorization: AuthenticationContext,
        bucket: String,
        keyFilter: String?,
        valueSearch: String?,
    ): List<NatsKeyValueEntry> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getKeyValueEntries(bucket, keyFilter, valueSearch)
    }

    @Field
    suspend fun streamMessages(
        authorization: AuthenticationContext,
        streamName: String,
        limit: Int?,
        subjectFilter: String?,
        fromSeq: Long?,
        toSeq: Long?,
        dataSearch: String?,
    ): List<NatsStreamMessage> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return natsMonitoringService.getStreamMessages(
            streamName,
            limit ?: 50,
            subjectFilter,
            fromSeq,
            toSeq,
            dataSearch,
        )
    }
}
