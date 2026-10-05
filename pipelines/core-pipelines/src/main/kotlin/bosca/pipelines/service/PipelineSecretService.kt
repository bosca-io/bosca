package bosca.pipelines.service

import bosca.pipelines.model.PipelineSecret
import bosca.service.Service

/**
 * The node-secret store: named credentials a node references by name and
 * resolves at execution, kept AES/GCM-encrypted at rest and **never** persisted in a pipeline graph,
 * run snapshot, or trace/log. Management ([setSecret]/[listSecrets]/[deleteSecret]) is admin-gated at
 * the GraphQL layer; [resolveForExecution] is what a node calls at run time.
 */
interface PipelineSecretService : Service {

    /** Create or replace the secret [name] with [value] (encrypted before storage). Returns its metadata. */
    suspend fun setSecret(name: String, value: String): PipelineSecret

    /** All secrets' metadata (name + timestamps) for management UIs — never the value. */
    suspend fun listSecrets(): List<PipelineSecret>

    /** Remove the secret [name] (no-op if absent). */
    suspend fun deleteSecret(name: String)

    /** The decrypted value of [name], or `null` if there is no such secret. For execution-time resolution. */
    suspend fun resolve(name: String): String?

    /**
     * Resolve [name] for a node **at execution**: the decrypted value, or — when [dryRun] is set —
     * [SECRET_MASK], so a dry-run trace (and anything derived from it) never carries a real secret.
     * Throws when the secret is missing in a real run, so a misconfigured node fails loudly.
     */
    suspend fun resolveForExecution(name: String, dryRun: Boolean): String =
        if (dryRun) SECRET_MASK
        else resolve(name) ?: error("Pipeline secret '$name' is not defined")

    companion object {
        /** The placeholder a dry run sees instead of a real secret value. */
        const val SECRET_MASK = "***"
    }
}
