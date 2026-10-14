package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.guidance.*

interface WorkOpsGuidanceService : Service {
    /**
     * Resolves bundled defaults, platform settings, and the optional project's overrides.
     * Revisions include effective content and override choices, excluding hidden upstream changes.
     */
    suspend fun effective(projectId: UUID? = null): WorkOpsGuidance

    /**
     * Atomically replaces this scope's policy and document overrides. Null scope means platform settings.
     * [expectedRevision] is the effective revision loaded by the editor, including inherited settings.
     * A stale revision fails before writing documents, resources, or configuration.
     * Equivalent guides retain their document snapshots and resources; no-op saves retain the revision.
     */
    suspend fun save(projectId: UUID?, input: WorkOpsGuidanceInput, expectedRevision: String): WorkOpsGuidance

    /** Removes this scope's overrides and resources only when [expectedRevision] is still current. */
    suspend fun reset(projectId: UUID?, expectedRevision: String): WorkOpsGuidance

    /** Parses a portable SKILL.md into a guide for preview; does not change stored policy or documents. */
    fun importSkill(phase: WorkOpsGuidancePhase, skill: String): WorkOpsGuideInput

    /** Exports one effective guide as SKILL.md. Policy remains a separate, typed configuration. */
    suspend fun exportSkill(projectId: UUID?, phase: WorkOpsGuidancePhase): String
}
