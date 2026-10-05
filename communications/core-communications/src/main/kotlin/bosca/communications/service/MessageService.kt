package bosca.communications.service

import bosca.communications.model.Message
import bosca.service.Service

/**
 * Service responsible for dispatching messages to recipients across configured channels (e.g., email).
 *
 * Provides both asynchronous (queued) and synchronous delivery options for [Message] instances.
 */
interface MessageService : Service {

    /**
     * Enqueues a message for asynchronous delivery via the configured job queue.
     *
     * The message will be processed and delivered in the background by the messaging job system.
     * The message must specify at least one channel and one recipient.
     *
     * @param message the message to enqueue, including channels, recipients, and content
     */
    suspend fun send(message: Message)

    /**
     * Delivers a message synchronously, dispatching it immediately through all specified channels.
     *
     * Unlike [send], this method blocks until the message has been handed off to each channel's
     * mailer. The message must specify at least one channel and one recipient. It must not be
     * called inside a database transaction because the external handoff cannot be rolled back;
     * use [send] when the caller is transactional.
     *
     * @param message the message to deliver immediately
     */
    suspend fun sendNow(message: Message)
}
