package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionType
import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionSupplementaryControllerCoverageTest {

    private val controller = CollectionSupplementaryController()

    private fun createCollection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "Test Collection",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "published"
    )

    private fun createSupplementary(
        id: UUID = UUID.random(),
        collectionId: UUID = UUID.random(),
        key: String = "test-key",
        name: String = "Test Supplementary",
        planId: UUID? = null,
        created: OffsetDateTime = OffsetDateTime.now(),
        modified: OffsetDateTime = OffsetDateTime.now(),
        uploaded: OffsetDateTime? = null
    ) = CollectionSupplementary(
        id = id,
        collectionId = collectionId,
        key = key,
        name = name,
        planId = planId,
        created = created,
        modified = modified,
        uploaded = uploaded
    )

    @Test
    fun `planId returns supplementary planId when present`() {
        val planId = UUID.random()
        val supplementary = createSupplementary(planId = planId)
        val context = CollectionSupplementaryContext(createCollection(), supplementary)

        assertEquals(planId, controller.planId(context))
    }

    @Test
    fun `planId returns null when supplementary planId is null`() {
        val supplementary = createSupplementary(planId = null)
        val context = CollectionSupplementaryContext(createCollection(), supplementary)

        assertNull(controller.planId(context))
    }

    @Test
    fun `created returns supplementary created`() {
        val created = OffsetDateTime.now().minusDays(2)
        val supplementary = createSupplementary(created = created)
        val context = CollectionSupplementaryContext(createCollection(), supplementary)

        assertEquals(created, controller.created(context))
    }

    @Test
    fun `modified returns supplementary modified`() {
        val modified = OffsetDateTime.now().minusHours(3)
        val supplementary = createSupplementary(modified = modified)
        val context = CollectionSupplementaryContext(createCollection(), supplementary)

        assertEquals(modified, controller.modified(context))
    }

    @Test
    fun `uploaded returns supplementary uploaded when present`() {
        val uploaded = OffsetDateTime.now().minusMinutes(30)
        val supplementary = createSupplementary(uploaded = uploaded)
        val context = CollectionSupplementaryContext(createCollection(), supplementary)

        assertEquals(uploaded, controller.uploaded(context))
    }

    @Test
    fun `uploaded returns null when supplementary uploaded is null`() {
        val supplementary = createSupplementary(uploaded = null)
        val context = CollectionSupplementaryContext(createCollection(), supplementary)

        assertNull(controller.uploaded(context))
    }
}
