package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.release.ReleaseStoreTelemetry

/** Resolves live store state and analytics-backed observations for a WorkOps release. */
interface StoreTelemetryService : Service {
    /** Reads every configured Play/App Store target and its ingested history for [releaseId]. */
    suspend fun forRelease(releaseId: UUID): ReleaseStoreTelemetry
}
