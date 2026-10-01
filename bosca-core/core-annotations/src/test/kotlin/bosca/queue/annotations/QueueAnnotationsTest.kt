package bosca.queue.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class QueueAnnotationsTest {

    data class TestJobPayload(val data: String) : IJobDefinition

    data class TestMetadataJob(
        override val id: Uuid,
        override val version: Int?
    ) : IMetadataJobDefinition

    data class TestCollectionJob(
        override val id: Uuid
    ) : ICollectionJobDefinition

    @JobDefinition(definition = TestJobPayload::class, queue = "default", name = "test-job")
    class TestJobExecutor

    @Test
    fun `IJobDefinition can be implemented`() {
        val job = TestJobPayload("test")
        assertEquals("test", job.data)
    }

    @Test
    fun `IMetadataJobDefinition carries id and version`() {
        val id = Uuid.random()
        val job = TestMetadataJob(id = id, version = 3)
        assertEquals(id, job.id)
        assertEquals(3, job.version)
    }

    @Test
    fun `IMetadataJobDefinition version can be null`() {
        val job = TestMetadataJob(id = Uuid.random(), version = null)
        assertEquals(null, job.version)
    }

    @Test
    fun `ICollectionJobDefinition carries id`() {
        val id = Uuid.random()
        val job = TestCollectionJob(id = id)
        assertEquals(id, job.id)
    }

    @Test
    fun `JobDefinition annotation stores definition class`() {
        val annotation = TestJobExecutor::class.java.getAnnotation(JobDefinition::class.java)
        assertNotNull(annotation)
        assertEquals(TestJobPayload::class, annotation.definition)
    }

    @Test
    fun `JobDefinition annotation stores queue name`() {
        val annotation = TestJobExecutor::class.java.getAnnotation(JobDefinition::class.java)
        assertNotNull(annotation)
        assertEquals("default", annotation.queue)
    }

    @Test
    fun `JobDefinition annotation stores job name`() {
        val annotation = TestJobExecutor::class.java.getAnnotation(JobDefinition::class.java)
        assertNotNull(annotation)
        assertEquals("test-job", annotation.name)
    }
}
