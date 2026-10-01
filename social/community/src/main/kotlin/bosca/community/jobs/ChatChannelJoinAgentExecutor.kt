package bosca.community.jobs

import bosca.community.configuration.Constants
import bosca.community.configuration.JobQueueNames
import bosca.community.service.ChatService
import bosca.di.provide
import bosca.profile.profile.service.ProfileService
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(ChatChannelJoinAgentJob::class, JobQueueNames.communityJobQueue, "join-agent")
class ChatChannelJoinAgentExecutor : AbstractJobExecutor<ChatChannelJoinAgentJob>(ChatChannelJoinAgentJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val chatService: ChatService = provide()
        val profileService: ProfileService = provide()
        val securityService: SecurityService = provide()

        val channel = chatService.getById(job.channelId)
        if (channel?.groupId == null) {
            log.debug("Skipping Buddy auto-join: channel {} is not a community-group channel", job.channelId)
            return
        }

        log.info("Auto-joining channel: ${job.channelId}")

        val principal = checkNotNull(
            securityService.getPrincipalByIdentifier(Constants.COMPANION_NAME)?.takeIf { it.deletedAt == null },
        ) { "Buddy principal is not installed or active" }
        val profile = checkNotNull(profileService.getByPrincipal(principal.id).firstOrNull { !it.isDeleted }) {
            "Buddy profile is not installed or active"
        }

        chatService.joinChannel(
            channelId = job.channelId,
            profileId = profile.id,
            role = "guide"
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatChannelJoinAgentExecutor::class.java)
    }
}
