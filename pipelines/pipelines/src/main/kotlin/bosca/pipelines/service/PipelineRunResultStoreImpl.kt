@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * [PipelineRunResultStore] backed by [ObjectStorageService] (message passing). A
 * suspended node's pending output — its value plus the routing port — is a [PipelineRunNodeResult]
 * stored as a small object keyed by `(runId, nodeId)`, keeping the payload out of Postgres rows and
 * out of the job payload (which the broker, e.g. NATS, size-limits). The correlation that locates it
 * rides in the backing job's `Job.context`.
 *
 * Suspendable nodes **always** stage (a node with no inbound value stages JSON `null`), and the
 * resume's idempotency guards return before [get] on a duplicate — so by the time a run reads its
 * staged output the object is present. A genuinely absent object is therefore an error, surfaced (the
 * run fails) rather than masked as "no output".
 */
@ServiceImplementation
class PipelineRunResultStoreImpl(
    private val objectStorage: ObjectStorageService,
    private val json: Json,
) : PipelineRunResultStore {

    private fun path(runId: UUID, nodeId: String) = StringObjectPath("$PREFIX/$runId/$nodeId")

    override suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String?) {
        val wrapped = json.encodeToString(PipelineRunNodeResult.serializer(), PipelineRunNodeResult(result, port))
        objectStorage.setInputStream(path(runId, nodeId), wrapped.byteInputStream())
    }

    override suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult? {
        val raw = objectStorage.getString(path(runId, nodeId))
        return json.decodeFromString(PipelineRunNodeResult.serializer(), raw)
    }

    override suspend fun remove(runId: UUID, nodeId: String) = objectStorage.delete(path(runId, nodeId))

    private companion object {
        const val PREFIX = "pipeline-run-result"
    }
}
