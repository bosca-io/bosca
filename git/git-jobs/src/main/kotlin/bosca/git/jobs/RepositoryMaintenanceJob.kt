package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Job payload for running scheduled maintenance across all active git
 * repositories. Enqueues individual [RepositoryGcJob]s for each repository
 * to compact pack stores and clean up orphaned packs.
 */
@Serializable
class RepositoryMaintenanceJob : IJobDefinition
