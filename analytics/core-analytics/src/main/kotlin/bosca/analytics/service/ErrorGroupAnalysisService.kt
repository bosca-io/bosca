package bosca.analytics.service

import bosca.analytics.model.ErrorGroup

/**
 * Optional AI root-cause analysis for an error group. The default
 * (no-op) implementation simply returns the group unchanged with a
 * message indicating the analyzer is unavailable; the
 * `analytics-ai` module supplies a Claude-backed implementation that
 * generates and caches a short summary on the group row.
 *
 * Triggered on demand from the admin UI via the `analyze` mutation,
 * never automatically — keeps cost predictable and avoids burning
 * tokens on transient bugs that get fixed in the next deploy.
 */
interface ErrorGroupAnalysisService {

    /**
     * Generates (or refreshes) the AI summary for the group with the
     * given fingerprint and persists it via [ErrorGroupService]. Returns
     * the updated group.
     *
     * Implementations should be tolerant of missing or empty stack
     * traces and should never throw on transient AI provider failures
     * — they should log and return the group with the previous summary
     * intact.
     */
    suspend fun analyze(fingerprint: String): ErrorGroup
}
