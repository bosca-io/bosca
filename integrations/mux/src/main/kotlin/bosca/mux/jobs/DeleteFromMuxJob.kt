package bosca.mux.jobs

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.metadata.model.MediaConstants
import bosca.content.metadata.service.MediaService
import bosca.content.metadata.service.MetadataService
import bosca.mux.client.MuxClient
import bosca.mux.configuration.MuxConfiguration
import bosca.mux.configuration.toClientConfig
import bosca.queue.annotations.IMetadataJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Job payload for deleting a previously-ingested asset from Mux when
 * the corresponding Bosca metadata entry is removed.
 *
 * Enqueue this job during the content deletion workflow so that the
 * Mux-side resource is cleaned up in lockstep with the Bosca-side
 * removal.
 */
@Serializable
data class DeleteFromMuxJob(
    override val id: UUID,
    override val version: Int? = null,
    val assetId: String? = null,
) : IMetadataJobDefinition

/**
 * Executor that removes a Mux asset when the associated Bosca content
 * is deleted.
 *
 * Reads the Mux asset ID from the media record's provider attributes,
 * calls the Mux delete API, and then removes the media database record
 * so the data stays consistent.
 */
@JobDefinition(DeleteFromMuxJob::class, MuxJobQueueNames.muxJobQueue, "mux-delete")
class DeleteFromMuxExecutor(
    private val metadataService: MetadataService,
    private val configurationService: ConfigurationService,
    private val mediaService: MediaService,
    private val muxClient: MuxClient,
    private val json: Json,
) : AbstractJobExecutor<DeleteFromMuxJob>(DeleteFromMuxJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()

        val config = configurationService.getValueAs<MuxConfiguration>("mux", json)
            ?: throw FailException("Mux configuration not found")

        val assetId = job.assetId ?: resolveAssetIdFromDatabase(job.id, job.version)
        if (assetId == null) {
            log.info("No Mux asset ID found for metadata {}, nothing to delete", job.id)
            return
        }

        muxClient.deleteAsset(config.toClientConfig(), assetId)
        log.info("Deleted Mux asset {} for metadata {}", assetId, job.id)

        mediaService.deleteMedia(job.id)
    }

    /**
     * Attempts to resolve the Mux asset ID by looking up the media record
     * from the database. Returns null if the metadata or media record no
     * longer exists, or if the media record has no asset ID.
     */
    private suspend fun resolveAssetIdFromDatabase(metadataId: UUID, version: Int?): String? {
        val metadata = metadataService.getById(metadataId, version)
        if (metadata == null) {
            log.info("Metadata {} already deleted, cleaning up media record if present", metadataId)
            mediaService.deleteMedia(metadataId)
            return null
        }

        val media = mediaService.getMedia(metadata.id)
        if (media == null) {
            log.info("No media record found for metadata {}, nothing to delete", metadataId)
            return null
        }

        val assetId = media.providerAttributes.jsonObject[MediaConstants.ATTR_ASSET_ID]?.jsonPrimitive?.content
        if (assetId == null) {
            log.warn("Media record for metadata {} has no assetId in provider attributes", metadataId)
            mediaService.deleteMedia(metadata.id)
        }
        return assetId
    }

    companion object {
        private val log = LoggerFactory.getLogger(DeleteFromMuxExecutor::class.java)
    }
}
