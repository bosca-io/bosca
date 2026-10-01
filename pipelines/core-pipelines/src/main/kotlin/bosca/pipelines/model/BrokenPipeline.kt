package bosca.pipelines.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * A stored pipeline whose graph JSON no longer decodes against the loaded node registry — a node type
 * that was renamed or removed, a module no longer loaded, or corrupt graph data.
 *
 * Such a pipeline can't form a [Pipeline] (its nodes won't deserialize), so it is silently dropped from
 * [bosca.pipelines.service.PipelineService.getAll] and throws from `get(id)` — leaving it invisible and
 * unmanageable through the normal surface. This lightweight projection is read straight from the record
 * (no graph decode), so an admin can see the broken pipelines and delete them.
 */
@Serializable
data class BrokenPipeline(
    val id: UUID,
    val name: String,
    val key: String,
    /** Why the graph failed to decode (the deserialization error message). */
    val error: String,
)
