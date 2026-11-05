package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitRuntimeJob
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/** Uses the established content queue, already processed by Bosca Runner. */
@JobDefinition(definition = KitRuntimeJob::class, queue = "contentQueue", name = "kit-runtime")
class KitRuntimeJobExecutor(private val driver: KitRuntimeDriver) : AbstractJobExecutor<KitRuntimeJob>(KitRuntimeJob.serializer()) {
    override suspend fun execute() { driver.reconcile(getJobDefinition().sessionId) }
}
