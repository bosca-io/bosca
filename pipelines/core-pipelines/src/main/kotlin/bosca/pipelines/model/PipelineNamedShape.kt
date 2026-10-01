package bosca.pipelines.model

import bosca.pipelines.node.ShapeField
import kotlinx.serialization.Serializable

/**
 * A reusable, **named** object shape — a first-class type an Input/Output references by name (stored as
 * `"shape:<name>"`), so a map of typed fields (e.g. `{ repositories: ProjectRepository[], version: Version }`)
 * is defined once as `ReleaseBundle` and reused across pipelines. Global, keyed by [name].
 */
@Serializable
data class PipelineNamedShape(
    val name: String,
    val fields: List<ShapeField> = emptyList(),
)
