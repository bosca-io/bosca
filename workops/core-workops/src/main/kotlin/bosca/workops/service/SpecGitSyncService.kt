package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.spec.Spec

interface SpecGitSyncService : Service {

    /** Pushes supplied content, preferring the current saved rich document for Markdown bindings. */
    suspend fun pushToGit(spec: Spec, content: String, actingPrincipalId: UUID, authorName: String, authorEmail: String): String?

    /** Pushes the current saved Content document as Markdown or its complete Content JSON wrapper. Missing saved content fails before committing. */
    suspend fun pushSavedToGit(spec: Spec, actingPrincipalId: UUID, authorName: String, authorEmail: String): String?

    suspend fun pullFromGit(specId: UUID, commitSha: String, actingPrincipalId: UUID, actingProfileId: UUID?): Spec?

    suspend fun onPushEvent(repositoryId: UUID, ref: String, beforeSha: String, afterSha: String, changedFiles: Set<String>)
}
