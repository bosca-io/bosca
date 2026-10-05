package bosca.content.transition.jobs

import bosca.content.transition.model.BeginTransitionInput
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionTransitionJobTest {

    @Test
    fun `CollectionTransitionJob without languageTag defaults to null`() {
        val job = CollectionTransitionJob(id = UUID.random())
        assertNull(job.languageTag)
    }

    @Test
    fun `CollectionTransitionJob carries languageTag`() {
        val id = UUID.random()
        val job = CollectionTransitionJob(id = id, languageTag = "es")
        assertEquals(id, job.id)
        assertEquals("es", job.languageTag)
    }

    @Test
    fun `BeginTransitionInput without languageTag defaults to null`() {
        val input = BeginTransitionInput(
            collectionId = UUID.random(),
            stateId = "published",
            status = "test"
        )
        assertNull(input.languageTag)
    }

    @Test
    fun `BeginTransitionInput carries languageTag`() {
        val collectionId = UUID.random()
        val input = BeginTransitionInput(
            collectionId = collectionId,
            stateId = "published",
            status = "test",
            languageTag = "fr"
        )
        assertEquals(collectionId, input.collectionId)
        assertEquals("published", input.stateId)
        assertEquals("fr", input.languageTag)
    }

    @Test
    fun `BeginTransitionInput copy preserves languageTag`() {
        val input = BeginTransitionInput(
            collectionId = UUID.random(),
            stateId = "draft",
            status = "test",
            languageTag = "de"
        )
        val copied = input.copy(stateId = "advertised")
        assertEquals("advertised", copied.stateId)
        assertEquals("de", copied.languageTag)
    }

    @Test
    fun `BeginTransitionInput copy can clear languageTag`() {
        val input = BeginTransitionInput(
            collectionId = UUID.random(),
            stateId = "draft",
            status = "test",
            languageTag = "de"
        )
        val copied = input.copy(languageTag = null)
        assertNull(copied.languageTag)
    }

    @Test
    fun `BeginTransitionInput for metadata has no languageTag by default`() {
        val input = BeginTransitionInput(
            metadataId = UUID.random(),
            version = 1,
            stateId = "published",
            status = "test"
        )
        assertNull(input.languageTag)
        assertNull(input.collectionId)
        assertEquals(1, input.version)
    }

    @Test
    fun `CollectionTransitionJob preserves id across copy`() {
        val id = UUID.random()
        val job = CollectionTransitionJob(id = id, languageTag = "fr")
        val copied = job.copy(languageTag = "de")
        assertEquals(id, copied.id)
        assertEquals("de", copied.languageTag)
    }

    @Test
    fun `BeginTransitionInput with all variant transition fields`() {
        val collectionId = UUID.random()
        val input = BeginTransitionInput(
            collectionId = collectionId,
            stateId = "published",
            status = "Publishing variant",
            languageTag = "es",
            restart = true,
        )
        assertEquals(collectionId, input.collectionId)
        assertEquals("published", input.stateId)
        assertEquals("Publishing variant", input.status)
        assertEquals("es", input.languageTag)
        assertEquals(true, input.restart)
    }

    @Test
    fun `BeginTransitionInput restart defaults to null`() {
        val input = BeginTransitionInput(
            collectionId = UUID.random(),
            stateId = "draft",
            status = "test"
        )
        assertNull(input.restart)
    }
}
