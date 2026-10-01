package bosca.communications.service

import bosca.communications.model.BmlMessageProject
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.ResolvedMessageTemplate
import bosca.serialization.UUID
import bosca.service.Service

/**
 * The BML message registry: which message projects exist and which published
 * artifact version is pinned for each (null = the message server's active/latest version).
 *
 * Registration is optional for sending — it exists to carry the pin and provenance. Rolling a
 * project back is a registry update, never a redeploy.
 */
interface BmlMessageRegistryService : Service {

    suspend fun listProjects(): List<BmlMessageProject>

    suspend fun getProject(projectKey: String): BmlMessageProject?

    /** Register or update the metadata of a message project. The operation is idempotent and keeps any pin. */
    suspend fun registerProject(projectKey: String, description: String? = null, repositoryId: UUID? = null): BmlMessageProject

    /**
     * Pin [projectKey]'s sends to a published artifact [version], or clear the pin with null
     * (back to the active/latest version). Data-driven rollback — no redeploy. Throws when the
     * project is not registered.
     */
    suspend fun pinProjectVersion(projectKey: String, version: String?): BmlMessageProject

    /** Remove a project registration. Returns false when unknown. */
    suspend fun removeProject(projectKey: String): Boolean

    /**
     * Resolve a message's template reference for rendering: the reference passes through, and
     * a registered project contributes its version pin (unregistered projects carry none).
     */
    suspend fun resolve(template: MessageBmlTemplate): ResolvedMessageTemplate
}
