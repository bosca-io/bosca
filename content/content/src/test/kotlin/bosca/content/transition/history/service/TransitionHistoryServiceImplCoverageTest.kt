package bosca.content.transition.history.service

import bosca.content.transition.history.model.CollectionTransitionHistory
import bosca.content.transition.history.model.MetadataTransitionHistory
import bosca.content.transition.history.repository.CollectionTransitionHistoryRepository
import bosca.content.transition.history.repository.MetadataTransitionHistoryRepository
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit coverage for [TransitionHistoryServiceImpl]. The service is a pure delegator to two
 * KSP-generated repositories (`@Generated`, excluded from coverage), so mocking the repository
 * interfaces exercises the service's own two `add` overloads without any real SQL.
 */
class TransitionHistoryServiceImplCoverageTest {

    private val metadataRepository = mockk<MetadataTransitionHistoryRepository>()
    private val collectionRepository = mockk<CollectionTransitionHistoryRepository>()

    private val service = TransitionHistoryServiceImpl(metadataRepository, collectionRepository)

    @AfterTest
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `add metadata history delegates to metadata repository and returns its result`() = runTest {
        val input = MetadataTransitionHistory(
            metadataId = UUID.random(),
            fromStateId = "pending",
            toStateId = "processing",
            principal = UUID.random(),
            status = "ok",
            success = true,
            complete = false,
        )
        val persisted = input.copy(complete = true)
        coEvery { metadataRepository.add(input) } returns persisted

        val result = service.add(input)

        assertSame(persisted, result)
        assertEquals(persisted, result)
        coVerify(exactly = 1) { metadataRepository.add(input) }
        coVerify(exactly = 0) { collectionRepository.add(any()) }
    }

    @Test
    fun `add metadata history with null principal still delegates`() = runTest {
        val input = MetadataTransitionHistory(
            metadataId = UUID.random(),
            fromStateId = "draft",
            toStateId = "published",
            principal = null,
            status = "failed",
            success = false,
            complete = true,
        )
        coEvery { metadataRepository.add(input) } returns input

        val result = service.add(input)

        assertSame(input, result)
        coVerify(exactly = 1) { metadataRepository.add(input) }
    }

    @Test
    fun `add collection history delegates to collection repository and returns its result`() = runTest {
        val input = CollectionTransitionHistory(
            collectionId = UUID.random(),
            languageTag = "en",
            fromStateId = "pending",
            toStateId = "processing",
            principal = UUID.random(),
            status = "ok",
            success = true,
            complete = false,
        )
        val persisted = input.copy(status = "done", complete = true)
        coEvery { collectionRepository.add(input) } returns persisted

        val result = service.add(input)

        assertSame(persisted, result)
        assertEquals(persisted, result)
        coVerify(exactly = 1) { collectionRepository.add(input) }
        coVerify(exactly = 0) { metadataRepository.add(any()) }
    }

    @Test
    fun `add collection history with null language tag and principal still delegates`() = runTest {
        val input = CollectionTransitionHistory(
            collectionId = UUID.random(),
            languageTag = null,
            fromStateId = "draft",
            toStateId = "archived",
            principal = null,
            status = "failed",
            success = false,
            complete = true,
        )
        coEvery { collectionRepository.add(input) } returns input

        val result = service.add(input)

        assertSame(input, result)
        coVerify(exactly = 1) { collectionRepository.add(input) }
    }
}
