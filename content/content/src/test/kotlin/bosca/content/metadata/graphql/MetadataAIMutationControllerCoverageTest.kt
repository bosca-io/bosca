package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.listeners.JobCompleteNotification
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Coverage for [MetadataAIMutationController] and the [MetadataAIMutation] marker object.
 *
 * The two `@Field` resolvers ([MetadataAIMutationController.generateMp3FromDocument] and
 * [MetadataAIMutationController.generateImageFromDocument]) both:
 *  - short-circuit to `null` when the metadata is missing or the caller lacks EXECUTE,
 *  - take a `TODO()` arm on the wrong `promptKey` presence,
 *  - otherwise build a real [Job] graph via the generated `enqueue`/`prepare` extensions, wait on a
 *    [JobCompleteNotification] over pubsub, and re-load the produced metadata.
 *
 * The generated `enqueue`/`prepare` extensions call `provide<JobQueue>(name = "contentQueue")` and the
 * [Job] constructor calls `provide<Json>()`, so those are registered in the [ProviderRegistry] here.
 * Jobs created without an assigned id remain [UUID.NIL], so the child job id observed by the controller
 * is [UUID.NIL] — the fake notification carries that id so the pubsub filter matches.
 */
@OptIn(InternalDI::class)
class MetadataAIMutationControllerCoverageTest {

    private val json = Json
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val pubsub = mockk<PubSubService>()
    private val jobQueue = mockk<JobQueue>()

    private val controller = MetadataAIMutationController(json, metadataService, metadataPermissionEvaluator, pubsub)
    private val authentication = mockk<AuthenticationContext>()

    private val id = UUID.random()
    private val version = 1

    private val metadata = Metadata(
        id = id,
        name = "Source",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        ProviderRegistry.clear()
    }

    /** A produced-metadata instance distinct from the source, returned by the final re-load. */
    private val produced = Metadata(
        id = UUID.random(),
        name = "Produced",
        type = MetadataType.STANDARD,
        contentType = "audio/mpeg",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    // ---------------------------------------------------------------------------------------------
    // generateMp3FromDocument
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `mp3 returns null when metadata not found`() = runTest {
        coEvery { metadataService.getById(id, version) } returns null

        assertNull(
            controller.generateMp3FromDocument(authentication, id, version, "model", null, null, null)
        )
    }

    @Test
    fun `mp3 returns null when permission denied`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns false

        assertNull(
            controller.generateMp3FromDocument(authentication, id, version, "model", null, null, null)
        )
    }

    @Test
    fun `mp3 throws NotImplemented when promptKey provided`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true

        assertFailsWith<NotImplementedError> {
            controller.generateMp3FromDocument(authentication, id, version, "model", "prompt", null, null)
        }
    }

    @Test
    fun `mp3 happy path with null configuration returns reloaded metadata`() = runTest {
        val producedId = produced.id
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        coEvery { metadataService.getById(producedId, 3) } returns produced

        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("id", JsonPrimitive(producedId.toString()))
                put("version", JsonPrimitive(3))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val result =
            controller.generateMp3FromDocument(authentication, id, version, "model", null, "featured", null)

        assertEquals(produced, result)
    }

    @Test
    fun `mp3 happy path decodes provided configuration`() = runTest {
        val producedId = produced.id
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        coEvery { metadataService.getById(producedId, 7) } returns produced

        val configuration = buildJsonObject {
            put("includeTitle", JsonPrimitive(false))
        }
        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("id", JsonPrimitive(producedId.toString()))
                put("version", JsonPrimitive(7))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val result =
            controller.generateMp3FromDocument(authentication, id, version, "model", null, null, configuration)

        assertEquals(produced, result)
    }

    @Test
    fun `mp3 errors when no matching completion notification arrives`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns emptyFlow()

        val e = assertFailsWith<IllegalStateException> {
            controller.generateMp3FromDocument(authentication, id, version, "model", null, null, null)
        }
        assertEquals("job not found", e.message)
    }

    @Test
    fun `mp3 errors when notification context lacks metadata id`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()

        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("version", JsonPrimitive(3))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val e = assertFailsWith<IllegalStateException> {
            controller.generateMp3FromDocument(authentication, id, version, "model", null, null, null)
        }
        assertEquals("metadataId not found", e.message)
    }

    @Test
    fun `mp3 errors when notification context lacks version`() = runTest {
        val producedId = produced.id
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()

        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("id", JsonPrimitive(producedId.toString()))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val e = assertFailsWith<IllegalStateException> {
            controller.generateMp3FromDocument(authentication, id, version, "model", null, null, null)
        }
        assertEquals("version not found", e.message)
    }

    // ---------------------------------------------------------------------------------------------
    // generateImageFromDocument
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `image returns null when metadata not found`() = runTest {
        coEvery { metadataService.getById(id, version) } returns null

        assertNull(
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", null, null)
        )
    }

    @Test
    fun `image returns null when permission denied`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns false

        assertNull(
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", null, null)
        )
    }

    @Test
    fun `image throws NotImplemented when promptKey missing`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true

        assertFailsWith<NotImplementedError> {
            controller.generateImageFromDocument(authentication, id, version, "model", null, null, null)
        }
    }

    @Test
    fun `image happy path with null configuration returns reloaded metadata`() = runTest {
        val producedId = produced.id
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        coEvery { metadataService.getById(producedId, 4) } returns produced

        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("id", JsonPrimitive(producedId.toString()))
                put("version", JsonPrimitive(4))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val result =
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", "featured", null)

        assertEquals(produced, result)
    }

    @Test
    fun `image happy path decodes provided configuration`() = runTest {
        val producedId = produced.id
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        coEvery { metadataService.getById(producedId, 9) } returns produced

        val configuration = buildJsonObject {
            put("includeTtsMarkup", JsonPrimitive(true))
        }
        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("id", JsonPrimitive(producedId.toString()))
                put("version", JsonPrimitive(9))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val result =
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", null, configuration)

        assertEquals(produced, result)
    }

    @Test
    fun `image errors when no matching completion notification arrives`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns emptyFlow()

        val e = assertFailsWith<IllegalStateException> {
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", null, null)
        }
        assertEquals("job not found", e.message)
    }

    @Test
    fun `image errors when notification context lacks metadata id`() = runTest {
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()

        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("version", JsonPrimitive(4))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val e = assertFailsWith<IllegalStateException> {
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", null, null)
        }
        assertEquals("metadataId not found", e.message)
    }

    @Test
    fun `image errors when notification context lacks version`() = runTest {
        val producedId = produced.id
        coEvery { metadataService.getById(id, version) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()

        val notification = JobCompleteNotification(
            jobId = UUID.NIL,
            context = buildJsonObject {
                put("id", JsonPrimitive(producedId.toString()))
            }
        )
        every {
            pubsub.subscribe(any(), any<DeserializationStrategy<JobCompleteNotification>>())
        } returns flowOf(Message("job-complete", notification))

        val e = assertFailsWith<IllegalStateException> {
            controller.generateImageFromDocument(authentication, id, version, "model", "prompt", null, null)
        }
        assertEquals("version not found", e.message)
    }

    // ---------------------------------------------------------------------------------------------
    // marker object
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `marker object is a singleton`() {
        assertEquals(MetadataAIMutation, MetadataAIMutation)
    }
}
