package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.MetadataService
import bosca.graphql.Batch
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Covers [MetadataRelationshipController.metadata], the batched resolver delegation
 * left uncovered by [MetadataRelationshipControllerTest].
 */
class MetadataRelationshipControllerCoverageTest {

    private val service = mockk<MetadataService>()
    private val controller = MetadataRelationshipController(service)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `metadata delegates the batch to the service`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Metadata>(
            listOf(MetadataCacheKeyId(UUID.random(), 1))
        )
        val captured = slot<Batch<MetadataCacheKeyId, Metadata>>()
        coJustRun { service.getByIdBatched(capture(captured)) }

        controller.metadata(batch)

        coVerify(exactly = 1) { service.getByIdBatched(any()) }
        assertSame(batch, captured.captured)
    }

    @Test
    fun `metadata delegates an empty batch to the service`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Metadata>(emptyList())
        coJustRun { service.getByIdBatched(any()) }

        controller.metadata(batch)

        coVerify(exactly = 1) { service.getByIdBatched(batch) }
    }
}
