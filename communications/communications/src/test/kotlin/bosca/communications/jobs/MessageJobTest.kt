@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.communications.jobs

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class MessageJobTest {

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `executor delegates the queued message to the service`() = runBlocking {
        val messages = mockk<MessageService>()
        val queue = mockk<JobQueue>(relaxed = true)
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Hello",
            recipients = listOf(UUID.random()),
            content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
        )
        coEvery { messages.sendNow(message) } returns Unit
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(Message.serializer(), message),
            executor = MessageJob::class,
        )
        val executor = MessageJob(messages)

        withContext(queue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { messages.sendNow(message) }
    }
}
