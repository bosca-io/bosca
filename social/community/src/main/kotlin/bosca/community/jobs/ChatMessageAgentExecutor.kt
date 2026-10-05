package bosca.community.jobs

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import bosca.community.ai.agent.QuietCompanionAgent
import bosca.community.ai.agent.toContentPart
import bosca.community.ai.agent.toMessage
import bosca.community.configuration.Constants
import bosca.community.configuration.JobQueueNames
import bosca.community.service.ChatService
import bosca.di.provide
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.profile.profile.service.ProfileService
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.serialization.UUID
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

@JobDefinition(ChatMessageAgentJob::class, JobQueueNames.communityJobQueue, "message-agent")
class ChatMessageAgentExecutor : AbstractJobExecutor<ChatMessageAgentJob>(ChatMessageAgentJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val securityService: SecurityService = provide()
        val profileService: ProfileService = provide()
        val chatService: ChatService = provide()
        val principal = securityService.getPrincipalByIdentifier(Constants.COMPANION_NAME) ?: return
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return

        if (job.senderId == profile.id) return

        val channel = chatService.getById(job.channelId) ?: return

        if (channel.groupId == null) {
            log.debug("Skipping Buddy: channel {} is not a community-group channel", channel.id)
            return
        }

        log.info("Processing message for AI agent: ${job.sequence} in channel ${channel.name}")

        val executor = provide<PromptExecutor>()
        val agent = QuietCompanionAgent(executor)
        val history = chatService.getMessages(channel.id, before = job.sequence, limit = 20)
        val newMessage = Message.User(job.content.map { it.toContentPart() }, RequestMetaInfo.Empty)

        val response = agent.processMessage(
            channelName = channel.name,
            history = history.map { it.toMessage(profile.id) },
            newMessage = newMessage
        )

        if (response?.shouldRespond == true && response.response != null) {
            chatService.sendMessage(
                channelId = channel.id,
                clientId = responseClientId(job),
                senderId = profile.id,
                content = listOf(MessageContent(MessageContentType.TEXT, response.response)),
                attributes = null
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatMessageAgentExecutor::class.java)

        /** Keeps a retried agent job from posting a second response to the same source message. */
        internal fun responseClientId(job: ChatMessageAgentJob): UUID {
            val key = "community-agent:${job.channelId}:${job.sequence}"
            return UUID.parse(
                java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
            )
        }
    }
}
