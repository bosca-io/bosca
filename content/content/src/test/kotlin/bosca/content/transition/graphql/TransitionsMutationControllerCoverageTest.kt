package bosca.content.transition.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.ContentItem
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.model.Transition
import bosca.content.transition.model.TransitionInput
import bosca.content.transition.service.TransitionService
import bosca.content.transition.service.Transitioner
import bosca.di.ObjectProvider
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionsMutationControllerCoverageTest {

    private val service = mockk<TransitionService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val metadataService = mockk<MetadataService>()
    private val metadataServiceProvider = mockk<ObjectProvider<MetadataService>>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val metadataPermissionEvaluatorProvider = mockk<ObjectProvider<MetadataPermissionEvaluator>>()
    private val collectionService = mockk<CollectionService>()
    private val collectionServiceProvider = mockk<ObjectProvider<CollectionService>>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val collectionPermissionEvaluatorProvider = mockk<ObjectProvider<CollectionPermissionEvaluator>>()
    private val transitioner = mockk<Transitioner>()

    private val controller = TransitionsMutationController(
        service = service,
        groupEvaluator = groupEvaluator,
        metadataServiceProvider = metadataServiceProvider,
        metadataPermissionEvaluatorProvider = metadataPermissionEvaluatorProvider,
        collectionServiceProvider = collectionServiceProvider,
        collectionPermissionEvaluatorProvider = collectionPermissionEvaluatorProvider,
        transitioner = transitioner,
    )

    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        workflowStateId: String = "draft",
    ) = Metadata(
        id = id,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        version = version,
    )

    private fun collection(
        id: UUID = UUID.random(),
        workflowStateId: String = "draft",
    ) = Collection(
        id = id,
        name = "test",
        languageTag = "en",
        workflowStateId = workflowStateId,
    )

    private fun languageVariant(
        id: UUID = UUID.random(),
    ) = CollectionLanguageVariant(
        id = id,
        languageTag = "fr",
        name = "test",
    )

    private fun principalAuth(): Principal {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        every { authenticatedPrincipal.id } returns principal.id
        return principal
    }

    // ---- marker object ----

    @Test
    fun `TransitionsMutation marker object is a singleton`() {
        assertEquals(TransitionsMutation, TransitionsMutation)
    }

    // ---- add ----

    @Test
    fun `add verifies manager group and delegates to service`() = runTest {
        val input = TransitionInput(
            description = "d",
            fromStateId = "draft",
            toStateId = "published",
            enterJobName = null,
            exitJobName = null,
            configuration = null,
        )
        val transition = Transition(
            fromStateId = "draft",
            toStateId = "published",
            description = "d",
            enterJobName = null,
            exitJobName = null,
            configuration = null,
        )
        every { groupEvaluator.verifyHasManagerGroup(authentication) } returns Unit
        coEvery { service.add(input) } returns transition

        assertEquals(transition, controller.add(authentication, input))
        coVerify(exactly = 1) { service.add(input) }
    }

    @Test
    fun `add throws when manager group check fails`() = runTest {
        val input = TransitionInput(
            description = "d",
            fromStateId = "draft",
            toStateId = "published",
            enterJobName = null,
            exitJobName = null,
            configuration = null,
        )
        every { groupEvaluator.verifyHasManagerGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> { controller.add(authentication, input) }
        coVerify(exactly = 0) { service.add(any()) }
    }

    // ---- edit ----

    @Test
    fun `edit verifies manager group and delegates to service`() = runTest {
        val input = TransitionInput(
            description = "d",
            fromStateId = "draft",
            toStateId = "published",
            enterJobName = "enter",
            exitJobName = "exit",
            configuration = null,
        )
        val transition = Transition(
            fromStateId = "draft",
            toStateId = "published",
            description = "d",
            enterJobName = "enter",
            exitJobName = "exit",
            configuration = null,
        )
        every { groupEvaluator.verifyHasManagerGroup(authentication) } returns Unit
        coEvery { service.edit(input) } returns transition

        assertEquals(transition, controller.edit(authentication, input))
    }

    @Test
    fun `edit throws when manager group check fails`() = runTest {
        val input = TransitionInput(
            description = "d",
            fromStateId = "draft",
            toStateId = "published",
            enterJobName = null,
            exitJobName = null,
            configuration = null,
        )
        every { groupEvaluator.verifyHasManagerGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> { controller.edit(authentication, input) }
        coVerify(exactly = 0) { service.edit(any()) }
    }

    // ---- delete ----

    @Test
    fun `delete verifies manager group and returns true`() = runTest {
        every { groupEvaluator.verifyHasManagerGroup(authentication) } returns Unit
        coEvery { service.delete("draft", "published") } returns Unit

        assertTrue(controller.delete(authentication, "draft", "published"))
        coVerify(exactly = 1) { service.delete("draft", "published") }
    }

    @Test
    fun `delete throws when manager group check fails`() = runTest {
        every { groupEvaluator.verifyHasManagerGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> { controller.delete(authentication, "draft", "published") }
        coVerify(exactly = 0) { service.delete(any(), any()) }
    }

    // ---- beginTransitions ----

    @Test
    fun `beginTransitions rejects oversized batch`() = runTest {
        val ids = (0..500).map { UUID.random() }

        assertFailsWith<IllegalArgumentException> {
            controller.beginTransitions(authentication, ids, "published", "status")
        }
    }

    @Test
    fun `beginTransitions filters items already in target state and transitions the rest`() = runTest {
        val alreadyPublished = metadata(workflowStateId = "published")
        val a = metadata(workflowStateId = "draft")
        val b = metadata(workflowStateId = "draft")
        val ids = listOf(alreadyPublished.id, a.id, b.id)
        val candidates = listOf(a, b)

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getByIds(ids) } returns listOf(alreadyPublished, a, b)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authentication, candidates, PermissionAction.EXECUTE)
        } returns candidates
        coEvery { transitioner.beginTransition(any(), any(), any()) } returns mockk<ContentItem>()

        val count = controller.beginTransitions(authentication, ids, "published", "status")

        assertEquals(2, count)
        coVerify(exactly = 2) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `beginTransitions returns zero when nothing is allowed`() = runTest {
        val a = metadata(workflowStateId = "draft")
        val ids = listOf(a.id)

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getByIds(ids) } returns listOf(a)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authentication, listOf(a), PermissionAction.EXECUTE)
        } returns emptyList()

        val count = controller.beginTransitions(authentication, ids, "published", "status")

        assertEquals(0, count)
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `beginTransitions swallows per-item failures and counts successes`() = runTest {
        val a = metadata(workflowStateId = "draft")
        val b = metadata(workflowStateId = "draft")
        val ids = listOf(a.id, b.id)
        val allowed = listOf(a, b)

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getByIds(ids) } returns listOf(a, b)
        coEvery {
            metadataPermissionEvaluator.filterAllowed(authentication, allowed, PermissionAction.EXECUTE)
        } returns allowed
        coEvery { transitioner.beginTransition(authentication, any(), a) } throws RuntimeException("boom")
        coEvery { transitioner.beginTransition(authentication, any(), b) } returns mockk<ContentItem>()

        val count = controller.beginTransitions(authentication, ids, "published", "status")

        assertEquals(1, count)
    }

    // ---- beginTransition (single) ----

    @Test
    fun `beginTransition on metadata verifies edit and returns true`() = runTest {
        val md = metadata()
        val request = BeginTransitionInput(metadataId = md.id, version = 1, stateId = "published", status = "s")

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getById(md.id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { transitioner.beginTransition(any(), any(), any()) } returns mockk<ContentItem>()

        assertTrue(controller.beginTransition(authentication, request))
        coVerify(exactly = 1) { transitioner.beginTransition(authentication, request) }
    }

    @Test
    fun `beginTransition on metadata returns false when not found`() = runTest {
        val id = UUID.random()
        val request = BeginTransitionInput(metadataId = id, version = 2, stateId = "published", status = "s")

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getById(id, 2) } returns null

        assertFalse(controller.beginTransition(authentication, request))
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `beginTransition on collection by id returns true`() = runTest {
        val col = collection()
        val request = BeginTransitionInput(collectionId = col.id, stateId = "published", status = "s")

        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { collectionService.getById(col.id) } returns col
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { transitioner.beginTransition(any(), any(), any()) } returns mockk<ContentItem>()

        assertTrue(controller.beginTransition(authentication, request))
        coVerify(exactly = 1) { collectionService.getById(col.id) }
    }

    @Test
    fun `beginTransition on collection language variant returns true`() = runTest {
        val id = UUID.random()
        val variant = languageVariant(id = id)
        val request = BeginTransitionInput(collectionId = id, languageTag = "fr", stateId = "published", status = "s")

        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { collectionService.getLanguageVariant(id, "fr") } returns variant
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) } returns Unit
        coEvery { transitioner.beginTransition(any(), any(), any()) } returns mockk<ContentItem>()

        assertTrue(controller.beginTransition(authentication, request))
        coVerify(exactly = 1) { collectionService.getLanguageVariant(id, "fr") }
    }

    @Test
    fun `beginTransition on collection returns false when not found`() = runTest {
        val id = UUID.random()
        val request = BeginTransitionInput(collectionId = id, stateId = "published", status = "s")

        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { collectionService.getById(id) } returns null

        assertFalse(controller.beginTransition(authentication, request))
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `beginTransition throws when neither metadata nor collection provided`() = runTest {
        val request = BeginTransitionInput(stateId = "published", status = "s")

        assertFailsWith<NotImplementedError> { controller.beginTransition(authentication, request) }
    }

    // ---- cancelTransition ----

    @Test
    fun `cancelTransition on metadata cancels job and fails pending state`() = runTest {
        val principal = principalAuth()
        val md = metadata(version = 3)

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getById(md.id, 3) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery {
            transitioner.cancelLatestJob(authentication, metadataId = md.id, metadataVersion = 3)
        } just Runs
        coEvery {
            metadataService.setPendingStateFailed(md, "Cancelled transition by user", principal)
        } returns md

        assertTrue(
            controller.cancelTransition(
                authentication,
                metadataId = md.id,
                metadataVersion = 3,
                collectionId = null,
                languageTag = null,
            )
        )
        coVerify(exactly = 1) { metadataService.setPendingStateFailed(md, "Cancelled transition by user", principal) }
    }

    @Test
    fun `cancelTransition on metadata defaults version to one when absent`() = runTest {
        principalAuth()
        val md = metadata(version = 1)

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getById(md.id, 1) } returns md
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { transitioner.cancelLatestJob(authentication, metadataId = md.id, metadataVersion = null) } just Runs
        coEvery { metadataService.setPendingStateFailed(md, any(), any()) } returns md

        assertTrue(
            controller.cancelTransition(
                authentication,
                metadataId = md.id,
                metadataVersion = null,
                collectionId = null,
                languageTag = null,
            )
        )
        coVerify(exactly = 1) { metadataService.getById(md.id, 1) }
    }

    @Test
    fun `cancelTransition on metadata returns false when not found`() = runTest {
        val id = UUID.random()

        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { metadataService.getById(id, 5) } returns null

        assertFalse(
            controller.cancelTransition(
                authentication,
                metadataId = id,
                metadataVersion = 5,
                collectionId = null,
                languageTag = null,
            )
        )
        coVerify(exactly = 0) { transitioner.cancelLatestJob(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelTransition on collection by id cancels job and fails pending state`() = runTest {
        val principal = principalAuth()
        val col = collection()

        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { collectionService.getById(col.id) } returns col
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery {
            transitioner.cancelLatestJob(authentication, collectionId = col.id, languageTag = null)
        } just Runs
        coEvery {
            collectionService.setPendingStateFailed(col, "Cancelled transition by user", principal)
        } returns col

        assertTrue(
            controller.cancelTransition(
                authentication,
                metadataId = null,
                metadataVersion = null,
                collectionId = col.id,
                languageTag = null,
            )
        )
        coVerify(exactly = 1) { collectionService.setPendingStateFailed(col, "Cancelled transition by user", principal) }
    }

    @Test
    fun `cancelTransition on collection language variant cancels job and fails pending state`() = runTest {
        val principal = principalAuth()
        val id = UUID.random()
        val variant = languageVariant(id = id)

        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { collectionService.getLanguageVariant(id, "fr") } returns variant
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) } returns Unit
        coEvery {
            transitioner.cancelLatestJob(authentication, collectionId = id, languageTag = "fr")
        } just Runs
        coEvery {
            collectionService.setPendingStateFailed(variant, "Cancelled transition by user", principal)
        } returns variant

        assertTrue(
            controller.cancelTransition(
                authentication,
                metadataId = null,
                metadataVersion = null,
                collectionId = id,
                languageTag = "fr",
            )
        )
        coVerify(exactly = 1) { collectionService.getLanguageVariant(id, "fr") }
    }

    @Test
    fun `cancelTransition on collection returns false when not found`() = runTest {
        val id = UUID.random()

        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { collectionService.getById(id) } returns null

        assertFalse(
            controller.cancelTransition(
                authentication,
                metadataId = null,
                metadataVersion = null,
                collectionId = id,
                languageTag = null,
            )
        )
        coVerify(exactly = 0) { transitioner.cancelLatestJob(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelTransition throws when neither metadata nor collection provided`() = runTest {
        assertFailsWith<NotImplementedError> {
            controller.cancelTransition(
                authentication,
                metadataId = null,
                metadataVersion = null,
                collectionId = null,
                languageTag = null,
            )
        }
    }
}
