package bosca.git.service

import bosca.git.model.ArtifactRequirement
import bosca.service.Service

/**
 * Verifies that a CI job's REQUIRED artifacts exist in the artifact registry — the
 * consumption dual of [ProducedArtifactVerifier]. The requirement checker gates a job's dispatch on
 * this: a job with unsatisfied requirements stays queued (visibly "waiting on artifacts") until its
 * providers publish, its deadline passes, or the run is cancelled.
 *
 * The registry lives in another module, so this is an SPI. Unlike produced-artifact verification —
 * where an absent implementation safely means "no verification" — a deployment that parses
 * `requires:` declarations but has no verifier CANNOT gate honestly: the checker must fail such
 * jobs loudly rather than dispatch-on-faith or hang forever.
 */
interface RequiredArtifactVerifier : Service {

    /**
     * The subset of [requirements] NOT satisfied in the registry (empty = all present). A coordinate
     * whose version segment ends in `*` is a prefix constraint — `my-lib:6.0.*` is satisfied by any
     * published `6.0.x` — mirroring the release-validation constraint language.
     */
    suspend fun unsatisfied(requirements: List<ArtifactRequirement>): List<ArtifactRequirement>
}
