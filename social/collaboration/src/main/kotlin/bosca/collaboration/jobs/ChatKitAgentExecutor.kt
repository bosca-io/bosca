package bosca.collaboration.jobs

import bosca.collaboration.service.ChatAgentService
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Executes Kit AI agent interactions triggered by @kit mentions or /kit
 * slash commands in chat channels. Delegates to [ChatAgentService] which
 * handles agent invocation, context assembly, and response posting.
 */
@JobDefinition(ChatKitAgentJob::class, "collaboration", "kit-chat-agent")
class ChatKitAgentExecutor : AbstractJobExecutor<ChatKitAgentJob>(ChatKitAgentJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val agentService: ChatAgentService = provide()

        log.info("Processing Kit agent request: sequence {} in channel {}", job.sequence, job.channelId)

        agentService.processAndRespond(
            channelId = job.channelId,
            senderId = job.senderId,
            sequence = job.sequence,
            slashCommand = job.slashCommand
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatKitAgentExecutor::class.java)
    }
}
