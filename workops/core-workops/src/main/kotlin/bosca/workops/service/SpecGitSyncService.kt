package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.spec.Spec

interface SpecGitSyncService : Service {

    suspend fun pushToGit(spec: Spec, content: String, actingPrincipalId: UUID, authorName: String, authorEmail: String): String?

    suspend fun pullFromGit(specId: UUID, commitSha: String, actingPrincipalId: UUID, actingProfileId: UUID?): Spec?

    suspend fun onPushEvent(repositoryId: UUID, ref: String, beforeSha: String, afterSha: String, changedFiles: Set<String>)
}
