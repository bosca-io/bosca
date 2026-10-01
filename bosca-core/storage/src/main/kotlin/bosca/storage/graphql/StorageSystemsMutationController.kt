package bosca.storage.graphql

import bosca.content.collection.jobs.CollectionIndexExecutor
import bosca.content.collection.jobs.CollectionIndexJob
import bosca.content.metadata.jobs.MetadataIndexExecutor
import bosca.content.metadata.jobs.MetadataIndexJob
import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.jobs.ProfileIndexExecutor
import bosca.profile.profile.jobs.ProfileIndexJob
import bosca.search.IndexStorageSystem
import bosca.search.configuration.JobQueueNames
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemInput
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

object StorageSystemsMutation

@TypeController
class StorageSystemsMutationController(
    private val storageSystemService: StorageSystemService,
    private val groups: GroupEvaluator,
    @ProviderName(JobQueueNames.indexJobQueue)
    private val indexJobQueue: JobQueue,
) : GraphQLController<StorageSystemsMutation> {

    private val log = LoggerFactory.getLogger(StorageSystemsMutationController::class.java)

    @Field
    suspend fun add(authenticationContext: AuthenticationContext, storageSystem: StorageSystemInput): StorageSystem {
        groups.verifyHasAdminGroup(authenticationContext)
        return storageSystemService.add(storageSystem)
    }

    @Field
    suspend fun edit(authenticationContext: AuthenticationContext, id: UUID, storageSystem: StorageSystemInput): StorageSystem {
        groups.verifyHasAdminGroup(authenticationContext)
        return storageSystemService.edit(id, storageSystem)
    }

    @Field
    suspend fun delete(authenticationContext: AuthenticationContext, id: UUID): Boolean {
        groups.verifyHasAdminGroup(authenticationContext)
        storageSystemService.delete(id)
        return true
    }

    @Field
    suspend fun reindex(
        authenticationContext: AuthenticationContext,
        storageName: String?,
        deleteFirst: Boolean?,
        metadata: Boolean?,
        collections: Boolean?,
        profiles: Boolean?,
        batchSize: Int?
    ): Boolean {
        groups.verifyHasAdminGroup(authenticationContext)
        val deleteFirst = deleteFirst ?: false
        if (metadata == null || metadata) {
            MetadataIndexJob(
                deleteFirst = deleteFirst,
                batchSize = batchSize,
                storage = storageName?.let {
                    IndexStorageSystem(
                        name = storageName
                    )
                }
            ).enqueue(indexJobQueue, MetadataIndexExecutor::class)
        }
        if (collections == null || collections) {
            CollectionIndexJob(
                deleteFirst = deleteFirst,
                batchSize = batchSize,
                storage = storageName?.let {
                    IndexStorageSystem(
                        name = storageName
                    )
                },
            ).enqueue(indexJobQueue, CollectionIndexExecutor::class)
        }
        if (profiles == null || profiles) {
            ProfileIndexJob(
                deleteFirst = deleteFirst,
                batchSize = batchSize,
                storage = storageName?.let {
                    IndexStorageSystem(
                        name = storageName
                    )
                }
            ).enqueue(indexJobQueue, ProfileIndexExecutor::class)
        }
        enqueueByName("reindex-all", buildJsonObject {})
        return true
    }

    private suspend fun enqueueByName(jobName: String, configuration: kotlinx.serialization.json.JsonElement) {
        try {
            val enqueuer = provide<JobConfigurationEnqueuer>(jobName)
            enqueuer.enqueue(configuration)
        } catch (_: Exception) {
            log.debug("Job enqueuer '{}' not available, skipping", jobName)
        }
    }
}
