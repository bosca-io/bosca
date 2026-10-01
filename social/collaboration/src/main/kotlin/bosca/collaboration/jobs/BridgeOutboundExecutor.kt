package bosca.collaboration.jobs

import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgeAdapterRegistry
import bosca.collaboration.bridge.BridgeService
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Relays Bosca chat messages to external platforms by iterating over active
 * bridge bindings for the channel, calling the appropriate platform adapter,
 * and recording the external message ID for edit/delete synchronization.
 *
 * The per-job dispatch logic lives in the companion's [dispatch] function
 * so it can be unit-tested without the surrounding DI / coroutine
 * `Job`-context plumbing — see `BridgeOutboundExecutorIntegrationTest`.
 */
@JobDefinition(BridgeOutboundJob::class, "collaboration", "bridge-outbound")
class BridgeOutboundExecutor : AbstractJobExecutor<BridgeOutboundJob>(BridgeOutboundJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        dispatch(job, provide(), provide(), provide())
    }

    companion object {
        private val log = LoggerFactory.getLogger(BridgeOutboundExecutor::class.java)

        /**
         * Executes a single outbound bridge dispatch: looks up bindings for
         * the job's channel, fetches the original message via the chat
         * service, and asks each platform's adapter to relay it. The
         * external message id returned by the adapter is recorded against
         * `(channelId, sequence, platform)` so subsequent edit/delete
         * operations can find it.
         *
         * Bindings whose platform doesn't have a registered adapter are
         * skipped with a warning rather than failing the whole job — that
         * keeps a half-configured Teams binding from blocking Slack
         * delivery on the same channel. Adapter failures are caught
         * per-binding for the same reason; the job-queue retry policy
         * applies at the [execute] boundary, not here.
         */
        suspend fun dispatch(
            job: BridgeOutboundJob,
            bridgeService: BridgeService,
            adapterRegistry: BridgeAdapterRegistry,
            chatService: ChatService,
        ) {
            val bindings = bridgeService.getBindingsForChannel(job.channelId)
            if (bindings.isEmpty()) return

            val message = chatService.getMessages(job.channelId, after = job.sequence - 1, limit = 1)
                .firstOrNull() ?: return

            for (binding in bindings) {
                if (!adapterRegistry.hasAdapter(binding.platform)) {
                    log.warn("No adapter for platform: {}", binding.platform)
                    continue
                }
                try {
                    val adapter = adapterRegistry.get(binding.platform)
                    val externalId = adapter.sendMessage(binding, message, job.senderName)
                    bridgeService.recordMessageMapping(job.channelId, job.sequence, binding.platform, externalId)
                } catch (e: Exception) {
                    log.error("Failed to bridge message to {}: {}", binding.platform, e.message, e)
                }
            }
        }
    }
}
