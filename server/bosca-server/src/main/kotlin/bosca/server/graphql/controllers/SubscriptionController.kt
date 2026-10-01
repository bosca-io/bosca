package bosca.server.graphql.controllers

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSessionStatus
import bosca.ai.chat.service.ChatHistoryService
import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.LiveSession
import bosca.content.collection.events.COLLECTION_STATE_CHANNEL
import bosca.content.collection.events.COLLECTION_UPDATED_CHANNEL
import bosca.content.collection.events.CollectionUpdated
import bosca.content.metadata.events.METADATA_STATE_CHANNEL
import bosca.content.metadata.events.METADATA_UPDATED_CHANNEL
import bosca.content.metadata.events.METADATA_UPLOAD_PROGRESS_CHANNEL
import bosca.content.metadata.events.MetadataUpdated
import bosca.experimentation.model.FlagUpdated
import bosca.experimentation.model.FLAG_UPDATED_CHANNEL
import bosca.content.metadata.events.UploadProgress
import bosca.content.timeevent.events.TIME_EVENT_CHANGED_CHANNEL
import bosca.content.timeevent.events.TimeEventChanged
import bosca.db.withConnectionManager
import bosca.di.provideBlockingNoSuspend
import bosca.graphql.GraphQLController
import bosca.graphql.SubscriptionRoot
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.service.PipelineRunService
import bosca.pubsub.PubSubService
import bosca.security.service.ApiTokenScope
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

object Subscription : SubscriptionRoot

@TypeController
class SubscriptionController(
    private val pubSubService: PubSubService,
    private val groups: GroupEvaluator,
    private val liveSessionStream: LiveSessionsService,
) : GraphQLController<Subscription> {

    private val chatHistoryService: ChatHistoryService by lazy {
        provideBlockingNoSuspend()
    }

    private fun <T> readSubscription(
        authenticationContext: AuthenticationContext,
        scope: ApiTokenScope,
        subscribe: () -> Flow<T>,
    ): Flow<T> = flow {
        groups.verifyHasScope(authenticationContext, scope.name)
        emitAll(subscribe())
    }

    @Field
    fun aiChatHistory(authenticationContext: AuthenticationContext, sessionId: UUID): Flow<ChatHistoryMessage> = flow {
        withConnectionManager {
            val session = chatHistoryService.getSession(sessionId) ?: throw IllegalArgumentException("Channel not found")
            if (session.principalId != authenticationContext.principal()?.id) throw SecurityException("Access denied")
        }
        emitAll(chatHistoryService.subscribeToMessages(sessionId))
    }

    @Field
    fun aiChatStatus(authenticationContext: AuthenticationContext, sessionId: UUID): Flow<ChatSessionStatus> = flow {
        withConnectionManager {
            val session = chatHistoryService.getSession(sessionId) ?: throw IllegalArgumentException("Channel not found")
            if (session.principalId != authenticationContext.principal()?.id) throw SecurityException("Access denied")
        }
        emitAll(chatHistoryService.subscribeToStatuses(sessionId))
    }

    @Field
    fun aiChatProcessing(authenticationContext: AuthenticationContext, sessionId: UUID): Flow<Boolean> = flow {
        withConnectionManager {
            val session = chatHistoryService.getSession(sessionId) ?: throw IllegalArgumentException("Session not found")
            if (session.principalId != authenticationContext.principal()?.id) throw SecurityException("Access denied")
        }
        emitAll(chatHistoryService.subscribeToProcessing(sessionId))
    }

    @Field
    fun metadata(authenticationContext: AuthenticationContext): Flow<MetadataEvent> =
        readSubscription(authenticationContext, ApiTokenScopes.CONTENT_VIEW) {
            merge(
                pubSubService.subscribe(METADATA_STATE_CHANNEL, MetadataUpdated.serializer()),
                pubSubService.subscribe(METADATA_UPDATED_CHANNEL, MetadataUpdated.serializer())
            ).map {
                MetadataEvent(
                    it.channel,
                    it.message.id,
                    it.message.version
                )
            }
        }

    // flagUpdated intentionally skips authentication. It only reveals flag
    // keys and lifecycle actions, not resolved values or targeting rules.
    // Client SDKs need this for real-time synchronization without credentials.
    @Field
    fun flagUpdated() = pubSubService.subscribe(
        FLAG_UPDATED_CHANNEL,
        FlagUpdated.serializer()
    ).map { it.message }

    @Field
    fun collection(authenticationContext: AuthenticationContext): Flow<CollectionEvent> =
        readSubscription(authenticationContext, ApiTokenScopes.COLLECTIONS_VIEW) {
            merge(
                pubSubService.subscribe(COLLECTION_STATE_CHANNEL, CollectionUpdated.serializer()),
                pubSubService.subscribe(COLLECTION_UPDATED_CHANNEL, CollectionUpdated.serializer())
            ).map {
                CollectionEvent(
                    it.channel,
                    it.message.id,
                    it.message.languageTag,
                )
            }
        }

    @Field
    fun uploadProgress(authenticationContext: AuthenticationContext, metadataId: UUID): Flow<UploadProgressEvent> =
        readSubscription(authenticationContext, ApiTokenScopes.CONTENT_VIEW) {
            pubSubService.subscribe(METADATA_UPLOAD_PROGRESS_CHANNEL, UploadProgress.serializer())
                .filter { it.message.metadataId == metadataId }
                .map {
                    UploadProgressEvent(
                        metadataId = it.message.metadataId,
                        bytesUploaded = it.message.bytesUploaded,
                        totalBytes = it.message.totalBytes,
                    )
                }
        }

    @Field
    fun timeEventChanged(authenticationContext: AuthenticationContext, metadataId: UUID): Flow<TimeEventChangedEvent> =
        readSubscription(authenticationContext, ApiTokenScopes.CONTENT_VIEW) {
            pubSubService.subscribe(TIME_EVENT_CHANGED_CHANNEL, TimeEventChanged.serializer())
                .filter { it.message.metadataId == metadataId }
                .map {
                    TimeEventChangedEvent(
                        metadataId = it.message.metadataId,
                        metadataVersion = it.message.metadataVersion,
                        eventCount = it.message.eventCount,
                    )
                }
        }

    /**
     * Live run + per-node status changes for a durable pipeline run. Admin-gated
     * to match the pipeline run queries; emits each [PipelineRunUpdate] the run service publishes on the run's
     * per-run channel. The update itself carries the new status, so the client updates without re-querying.
     */
    @Field
    fun pipelineRun(authenticationContext: AuthenticationContext, runId: UUID): Flow<PipelineRunUpdate> = flow {
        withConnectionManager { groups.verifyHasAdminGroup(authenticationContext) }
        emitAll(
            pubSubService.subscribe(PipelineRunService.runEventChannel(runId), PipelineRunUpdate.serializer())
                .map { it.message },
        )
    }

    /**
     * Live sessions for the live map. Streams the [LiveSessionsService] primitive's retained
     * snapshot and then live heartbeats as they arrive; [appId] filters to a single application (omit it
     * to span every application) and [appVersion] to a single version (omit it for all versions). Gated
     * to an authenticated principal because it reveals visitor locations and is operator-facing.
     */
    @Field
    fun liveSessions(
        authenticationContext: AuthenticationContext,
        appId: String?,
        appVersion: String?,
    ): Flow<LiveSession> = flow {
        withConnectionManager {
            authenticationContext.principal() ?: throw SecurityException("Authentication required")
        }
        emitAll(liveSessionStream.subscribe(appId, appVersion))
    }
}
