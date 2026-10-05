package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transformations.DocumentToTextConfiguration
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.jobs.ChirpToWavJob
import bosca.jobs.GoogleGenAIImageJob
import bosca.jobs.SupplementaryToMetadataJob
import bosca.jobs.WavToMp3Job
import bosca.jobs.enqueue
import bosca.jobs.prepare
import bosca.pubsub.PubSubService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.listeners.JOB_COMPLETE_CHANNEL
import bosca.sharedqueue.jobs.listeners.JobCompleteNotification
import bosca.sharedqueue.jobs.listeners.NotifyJobCompleteListener
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

object MetadataAIMutation

@TypeController
class MetadataAIMutationController(
    private val json: Json,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val pubsub: PubSubService
) : GraphQLController<MetadataAIMutation> {

    @Field
    suspend fun generateMp3FromDocument(authentication: AuthenticationContext, id: UUID, version: Int, modelKey: String, promptKey: String?, relationship: String?, configuration: JsonElement?): Metadata? {
        val metadata = metadataService.getById(id, version) ?: return null
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)) {
            return null
        }
        if (promptKey != null) {
            TODO()
        } else {
            val job = ChirpToWavJob(
                id = id,
                version = version,
                modelKey = modelKey,
                configuration = configuration?.let { json.decodeFromJsonElement<DocumentToTextConfiguration>(it) } ?: DocumentToTextConfiguration()
            ).enqueue {
                val job = WavToMp3Job(
                    id = id,
                    version = version
                ).prepare {
                    addChild(
                        SupplementaryToMetadataJob(
                            relationship = relationship
                        ).prepare {
                            addCallback(JobCallback(listener = NotifyJobCompleteListener::class))
                        }
                    )
                }
                addChild(job)
            }
            val childId = job.getChildren().first().getChildren().first().getId()
            val event = withTimeout(5.minutes) {
                pubsub.subscribe(JOB_COMPLETE_CHANNEL, JobCompleteNotification.serializer()).firstOrNull {
                    it.message.jobId == childId
                } ?: error("job not found")
            }
            val id = event.message.context.jsonObject["id"]?.jsonPrimitive?.let { UUID.parse(it.content) } ?: error("metadataId not found")
            val version = event.message.context.jsonObject["version"]?.jsonPrimitive?.int ?: error("version not found")
            return metadataService.getById(id, version)
        }
    }

    @Field
    suspend fun generateImageFromDocument(authentication: AuthenticationContext, id: UUID, version: Int, modelKey: String, promptKey: String?, relationship: String?, configuration: JsonElement?): Metadata? {
        val metadata = metadataService.getById(id, version) ?: return null
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)) {
            return null
        }
        if (promptKey == null) {
            TODO()
        } else {
            val job = GoogleGenAIImageJob(
                id = id,
                version = version,
                modelKey = modelKey,
                promptKey = promptKey,
                configuration = configuration?.let { json.decodeFromJsonElement<DocumentToTextConfiguration>(it) } ?: DocumentToTextConfiguration()
            ).enqueue {
                addChild(
                    SupplementaryToMetadataJob(
                        relationship = relationship
                    ).prepare {
                        addCallback(JobCallback(listener = NotifyJobCompleteListener::class))
                    }
                )
            }
            val childId = job.getChildren().first().getId()
            val event = withTimeout(5.minutes) {
                pubsub.subscribe(JOB_COMPLETE_CHANNEL, JobCompleteNotification.serializer()).firstOrNull {
                    it.message.jobId == childId
                } ?: error("job not found")
            }
            val id = event.message.context.jsonObject["id"]?.jsonPrimitive?.let { UUID.parse(it.content) } ?: error("metadataId not found")
            val version = event.message.context.jsonObject["version"]?.jsonPrimitive?.int ?: error("version not found")
            return metadataService.getById(id, version)
        }
    }
}