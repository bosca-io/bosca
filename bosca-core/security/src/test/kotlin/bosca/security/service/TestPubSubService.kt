package bosca.security.service

import bosca.pubsub.PubSubService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

internal fun testPubSubService(): PubSubService = mockk(relaxed = true) {
    every { subscribe(any(), any<DeserializationStrategy<String>>()) } returns emptyFlow()
    coEvery { publish(any(), any<SerializationStrategy<String>>(), any()) } returns Unit
}
