@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.storage.graphql

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.storage.service.StorageSystemService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class StorageSystemsMutationControllerTest {
    private val queue = mockk<JobQueue>(relaxed = true)
    private val gitReindex = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val storageService = mockk<StorageSystemService>()
    private val controller = StorageSystemsMutationController(storageService, groups, queue)
    private val authentication = AuthenticationContext(null, null)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<JobConfigurationEnqueuer>(name = "reindex-all") { gitReindex }
        every { queue.name } returns "index"
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `targeted rebuild retains its index and does not enqueue global Git rebuild`() = runTest {
        val captured = mutableListOf<Job>()
        io.mockk.coEvery { queue.enqueue(capture(captured)) } returns UUID.random()
        controller.reindex(authentication, "Admin Search Index", true, true, true, true, 50)

        coVerify(exactly = 3) { queue.enqueue(any()) }
        for (job in captured) {
            val definition = job.getDefinition() as JsonObject
            val storage = definition.getValue("storage") as JsonObject
            assertEquals(JsonPrimitive("Admin Search Index"), storage["name"])
            assertEquals(JsonPrimitive(true), definition["deleteFirst"])
            assertEquals(JsonPrimitive(50), definition["batchSize"])
        }
        coVerify(exactly = 0) { gitReindex.enqueue(any(), any()) }
    }

    @Test
    fun `targeted rebuild respects selected document kinds`() = runTest {
        val job = slot<Job>()
        io.mockk.coEvery { queue.enqueue(capture(job)) } returns UUID.random()
        controller.reindex(authentication, "Custom Search Index", false, false, true, false, null)

        coVerify(exactly = 1) { queue.enqueue(any()) }
        val definition = job.captured.getDefinition() as JsonObject
        assertEquals(JsonPrimitive("Custom Search Index"), (definition.getValue("storage") as JsonObject)["name"])
        coVerify(exactly = 0) { gitReindex.enqueue(buildJsonObject {}, any()) }
    }

    @Test
    fun `targeted Git rebuild forwards only its storage and skips content jobs`() = runTest {
        controller.reindex(authentication, "git-code", true, true, true, true, null)
        coVerify(exactly = 0) { queue.enqueue(any()) }
        coVerify(exactly = 1) { gitReindex.enqueue(match {
            ((it as? JsonObject)?.get("storage") as? JsonObject)?.get("name") == JsonPrimitive("git-code")
        }, any()) }
    }

    @Test
    fun `reindex all still includes global Git rebuild`() = runTest {
        controller.reindex(authentication, null, true, true, true, true, null)

        coVerify(exactly = 3) { queue.enqueue(any()) }
        coVerify(exactly = 1) { gitReindex.enqueue(buildJsonObject {}, any()) }
    }


    @Test
    fun `StorageSystemsMutation is a singleton object`() {
        val instance = StorageSystemsMutation
        assertNotNull(instance)
    }

    @Test
    fun `StorageSystemsMutation identity is stable`() {
        assertSame(StorageSystemsMutation, StorageSystemsMutation)
    }
}
