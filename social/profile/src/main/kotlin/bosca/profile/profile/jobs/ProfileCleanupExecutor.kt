@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.profile.profile.jobs

import bosca.di.ProviderRegistry
import bosca.profile.configuration.JobQueueNames
import bosca.profile.profile.service.ProfileCleanupHandler
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.coroutines.CancellationException

/** Runs every registered domain cleanup handler after a profile permanently loses its identity. */
@JobDefinition(
    ProfileCleanupJob::class,
    JobQueueNames.profileJobQueue,
    "profile-cleanup",
)
class ProfileCleanupExecutor :
    AbstractJobExecutor<ProfileCleanupJob>(ProfileCleanupJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        var failure: Exception? = null
        for (provider in ProviderRegistry.findAll(ProfileCleanupHandler::class)) {
            try {
                provider.get().onProfileCleanup(job.id, job.principalId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val firstFailure = failure
                if (firstFailure == null) failure = e else firstFailure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
