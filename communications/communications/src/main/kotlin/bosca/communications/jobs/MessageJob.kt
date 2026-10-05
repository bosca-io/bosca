package bosca.communications.jobs

import bosca.communications.configuration.JobQueueNames
import bosca.communications.model.Message
import bosca.communications.service.MessageService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/**
 * Processes queued messages by delegating to [MessageService.sendNow] for immediate delivery.
 *
 * Handles all message channels (email, push) that are enqueued for asynchronous processing.
 */
@JobDefinition(Message::class, JobQueueNames.messagesJobQueue, "send-message")
class MessageJob(
    private val messages: MessageService,
) : AbstractJobExecutor<Message>(Message.serializer()) {

    override suspend fun execute() {
        messages.sendNow(getJobDefinition())
    }
}
