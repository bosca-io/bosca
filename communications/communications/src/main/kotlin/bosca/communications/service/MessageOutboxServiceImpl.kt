package bosca.communications.service

import bosca.communications.configuration.JobQueueNames
import bosca.communications.jobs.MessageOutboxDeliveryJob
import bosca.communications.jobs.prepareDeliveryJob
import bosca.communications.model.Message
import bosca.communications.repository.MessageOutboxRepository
import bosca.di.annotation.ProviderName
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.FailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@ServiceImplementation
class MessageOutboxServiceImpl(
    private val repository: MessageOutboxRepository,
    private val messages: MessageService,
    private val json: Json,
    @ProviderName(JobQueueNames.messagesJobQueue)
    private val queue: JobQueue,
) : MessageOutboxService {

    override suspend fun enqueueOnce(sourceId: UUID, message: Message) {
        val deliveryMessages = message.partitionForAsyncDelivery()
        val encoded = json.encodeToString(ListSerializer(Message.serializer()), deliveryMessages)
        val created = repository.create(sourceId, encoded)
        val entries = created.ifEmpty { repository.getBySource(sourceId) }
        check(entries.isNotEmpty()) { "Message outbox batch $sourceId has no delivery rows" }
        entries.asSequence()
            .filter { it.sentAt == null }
            .forEach { entry ->
                queue.enqueueIfAbsent(MessageOutboxDeliveryJob(entry.id).prepareDeliveryJob())
            }
    }

    override suspend fun deliver(outboxId: UUID) {
        val entry = repository.getPending(outboxId) ?: return
        try {
            messages.sendNow(entry.message)
            check(markSent(entry.id) == 1) {
                "Pending message outbox row ${entry.id} disappeared before completion"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FailException) {
            // MessageService uses FailException only after an external provider accepted the
            // message. Keep that terminal result even if recording the local state also fails;
            // retrying the provider handoff would duplicate the notification.
            try {
                check(markSent(entry.id) == 1) {
                    "Pending message outbox row ${entry.id} disappeared before terminal completion"
                }
            } catch (cancellation: CancellationException) {
                cancellation.addSuppressed(e)
                throw cancellation
            } catch (markFailure: Exception) {
                e.addSuppressed(markFailure)
            }
            throw e
        } catch (e: Exception) {
            try {
                markFailure(entry.id, e.message ?: e::class.simpleName.orEmpty())
            } catch (cancellation: CancellationException) {
                cancellation.addSuppressed(e)
                throw cancellation
            } catch (markFailure: Exception) {
                e.addSuppressed(markFailure)
            }
            throw e
        }
    }

    private suspend fun markSent(outboxId: UUID): Int = withContext(NonCancellable) {
        repository.markSent(outboxId)
    }

    private suspend fun markFailure(outboxId: UUID, error: String): Int = withContext(NonCancellable) {
        repository.markFailure(outboxId, error)
    }

    override suspend fun recoverPending(limit: Int): Int {
        val entries = repository.getPending(limit.coerceIn(1, 1_000))
        entries.forEach { entry ->
            queue.enqueueIfAbsent(MessageOutboxDeliveryJob(entry.id).prepareDeliveryJob())
        }
        return entries.size
    }
}
