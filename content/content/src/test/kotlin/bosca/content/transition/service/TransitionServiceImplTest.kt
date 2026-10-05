package bosca.content.transition.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.content.transition.model.Transition
import bosca.content.transition.model.TransitionInput
import bosca.content.transition.repository.TransitionRepository
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.cache.withRequestCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(InternalDI::class)
class TransitionServiceImplTest {

    private val repository = mockk<TransitionRepository>(relaxed = true)
    private var service: TransitionServiceImpl

    init {
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        service = TransitionServiceImpl(repository)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private val config = JsonObject(emptyMap())

    private val transition = Transition(
        fromStateId = "draft",
        toStateId = "review",
        description = "Submit for review",
        enterJobName = "enter-review",
        exitJobName = "exit-draft",
        configuration = config
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @Test
    fun `getAll delegates to repository`() = runTest {
        withRequestCache {
            coEvery { repository.getAll() } returns listOf(transition)

            val result = service.getAll()

            assertEquals(1, result.size)
            assertEquals("draft", result[0].fromStateId)
        }
    }

    @Test
    fun `get finds transition by state pair`() = runTest {
        withRequestCache {
            coEvery { repository.findByFromStateIdAndToStateId("draft", "review") } returns transition

            val result = service.get("draft", "review")

            assertNotNull(result)
            assertEquals("Submit for review", result.description)
        }
    }

    @Test
    fun `get returns null when transition not found`() = runTest {
        withRequestCache {
            coEvery { repository.findByFromStateIdAndToStateId("draft", "published") } returns null

            val result = service.get("draft", "published")

            assertNull(result)
        }
    }

    @Test
    fun `add creates transition from input`() = runTest {
        withRequestCache {
            val input = TransitionInput(
                fromStateId = "review",
                toStateId = "published",
                description = "Publish",
                enterJobName = "enter-published",
                exitJobName = null,
                configuration = null
            )
            coEvery { repository.add(any()) } answers { firstArg() }

            val result = service.add(input)

            assertEquals("review", result.fromStateId)
            assertEquals("published", result.toStateId)
            assertEquals("Publish", result.description)
        }
    }

    @Test
    fun `edit updates existing transition`() = runTest {
        withRequestCache {
            val input = TransitionInput(
                fromStateId = "draft",
                toStateId = "review",
                description = "Updated description",
                enterJobName = "new-enter-job",
                exitJobName = null,
                configuration = config
            )
            coEvery { repository.findByFromStateIdAndToStateId("draft", "review") } returns transition
            coEvery { repository.update(any()) } answers { firstArg() }

            val result = service.edit(input)

            assertEquals("Updated description", result.description)
            assertEquals("new-enter-job", result.enterJobName)
            assertNull(result.exitJobName)
        }
    }

    @Test
    fun `edit throws when transition not found`() = runTest {
        withRequestCache {
            val input = TransitionInput(
                fromStateId = "nonexistent",
                toStateId = "other",
                description = "N/A",
                enterJobName = null,
                exitJobName = null,
                configuration = null
            )
            coEvery { repository.findByFromStateIdAndToStateId("nonexistent", "other") } returns null

            assertFailsWith<NoSuchElementException> {
                service.edit(input)
            }
        }
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        withRequestCache {
            service.delete("draft", "review")

            coVerify { repository.deleteByFromStateIdAndToStateId("draft", "review") }
        }
    }
}
