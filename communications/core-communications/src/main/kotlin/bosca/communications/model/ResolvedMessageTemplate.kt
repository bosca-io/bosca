package bosca.communications.model

import kotlinx.serialization.Serializable

/**
 * The outcome of resolving a [MessageBmlTemplate] against the BML email registry: the
 * concrete (project, template) to render — either taken directly from the reference or looked
 * up from its event key — plus the project's pinned artifact [version] when one is set
 * (null = the server's active version).
 */
@Serializable
data class ResolvedMessageTemplate(
    val project: String,
    val templateKey: String,
    val version: String? = null,
)
