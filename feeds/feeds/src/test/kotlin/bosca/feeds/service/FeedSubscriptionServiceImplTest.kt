@file:OptIn(ExperimentalUuidApi::class)

package bosca.feeds.service

import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSubscription
import bosca.feeds.repository.FeedSubscriptionRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**subscribe validates the source + upserts; unsubscribe reports prior presence; reads delegate. */
class FeedSubscriptionServiceImplTest {

    private val repository = mockk<FeedSubscriptionRepository>(relaxed = true)
    private val feedSourceService = mockk<FeedSourceService>()
    private lateinit var service: FeedSubscriptionServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = FeedSubscriptionServiceImpl(repository, feedSourceService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `subscribe validates the source and upserts the subscription`() = runTest {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        val source = FeedSource(sourceId = sourceId, url = "x")
        coEvery { feedSourceService.get(sourceId) } returns source

        assertEquals(source, service.subscribe(profileId, sourceId))
        coVerify(exactly = 1) { repository.add(profileId, sourceId) }
    }

    @Test
    fun `subscribe fails when the source does not exist`() = runTest {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        coEvery { feedSourceService.get(sourceId) } returns null

        assertFailsWith<IllegalStateException> { service.subscribe(profileId, sourceId) }
        coVerify(exactly = 0) { repository.add(any(), any()) }
    }

    @Test
    fun `unsubscribe deletes an existing subscription`() = runTest {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        coEvery { repository.get(profileId, sourceId) } returns FeedSubscription(profileId = profileId, sourceId = sourceId)

        assertEquals(true, service.unsubscribe(profileId, sourceId))
        coVerify(exactly = 1) { repository.delete(profileId, sourceId) }
    }

    @Test
    fun `unsubscribe returns false when not subscribed`() = runTest {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        coEvery { repository.get(profileId, sourceId) } returns null

        assertEquals(false, service.unsubscribe(profileId, sourceId))
        coVerify(exactly = 0) { repository.delete(any(), any()) }
    }

    @Test
    fun `getSubscribedSources delegates to the repository`() = runTest {
        val profileId = UUID.random()
        val sources = listOf(FeedSource(sourceId = UUID.random(), url = "x"))
        coEvery { repository.getSubscribedSources(profileId, 0, 25) } returns sources

        assertEquals(sources, service.getSubscribedSources(profileId, 0, 25))
    }
}
