package bosca.collaboration.jobs

import bosca.collaboration.federation.FederationMessageEnvelope
import bosca.collaboration.federation.FederationSyncDirection
import bosca.collaboration.federation.LocalPeerIdProvider
import bosca.collaboration.service.federationSubject
import bosca.di.provide
import bosca.pubsub.PubSubService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Relays a chat message across federation links by publishing a
 * [FederationMessageEnvelope] on each linked peer's federation subject.
 * INBOUND-only links are skipped — this is the outbound side, so we only
 * ship messages on links that are BIDIRECTIONAL or OUTBOUND.
 *
 * The actual NATS leaf-node bridging between peer clusters is configured
 * at the NATS server level; this executor publishes locally and lets the
 * server topology mirror the subject across peers.
 *
 * The originating peer's id is sourced from [LocalPeerIdProvider], which
 * persists a self-generated UUID via the configuration service on first
 * access so receiving peers can attribute messages to a stable source
 * identity across restarts.
 */
@JobDefinition(FederationOutboundJob::class, "collaboration", "federation-outbound")
class FederationOutboundExecutor :
    AbstractJobExecutor<FederationOutboundJob>(FederationOutboundJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        if (job.targets.isEmpty()) return

        val pubSubService: PubSubService = provide()
        val localPeerIdProvider: LocalPeerIdProvider = provide()
        val originPeerId = localPeerIdProvider.get()

        for (target in job.targets) {
            if (target.syncDirection == FederationSyncDirection.INBOUND) continue
            val envelope = FederationMessageEnvelope(
                originPeerId = originPeerId,
                originChannelId = job.channelId,
                originSenderId = job.senderId,
                originSequence = job.sequence,
                senderName = job.senderName,
                content = job.content,
                attributes = job.attributes,
            )
            try {
                pubSubService.publish(
                    channel = federationSubject(job.channelId),
                    serializer = FederationMessageEnvelope.serializer(),
                    message = envelope,
                )
            } catch (e: Exception) {
                log.error("Failed to publish federated message for channel {} sequence {} to peer {}: {}",
                    job.channelId, job.sequence, target.peerId, e.message, e)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FederationOutboundExecutor::class.java)
    }
}
