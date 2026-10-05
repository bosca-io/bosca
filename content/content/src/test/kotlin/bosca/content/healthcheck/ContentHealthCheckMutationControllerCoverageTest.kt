package bosca.content.healthcheck

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit coverage for [ContentHealthCheckMutationController.resolveUnpublishedRelationships].
 *
 * The controller drives its whole flow through mocked collaborators — [MetadataService],
 * [MetadataPermissionEvaluator], [Transitioner] and [GroupEvaluator] — plus the top-level
 * [bosca.db.transaction] helper (mocked static, executing the block inline). Every branch is
 * exercised: editor-group denial, parent-not-found, permission denial, missing principal, the
 * relationship loop with related-not-found continue, the published/unpublished and ready/not-ready
 * arms, and each of the public / publicContent / publicSupplementary conditionals.
 */
class ContentHealthCheckMutationControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val transitioner = mockk<Transitioner>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = ContentHealthCheckMutationController(
        metadataService,
        permissionEvaluator,
        transitioner,
        groupEvaluator
    )

    private val authentication = mockk<AuthenticationContext>()

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

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        clearAllMocks()
    }

    // ---- helpers -----------------------------------------------------------

    private fun metadata(
        id: UUID = UUID.random(),
        workflowStateId: String = "draft",
        ready: OffsetDateTime? = OffsetDateTime.now(),
        public: Boolean = false,
        publicContent: Boolean = false,
        publicSupplementary: Boolean = false,
    ): Metadata = Metadata(
        id = id,
        name = "item",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = workflowStateId,
        ready = ready,
        public = public,
        publicContent = publicContent,
        publicSupplementary = publicSupplementary,
    )

    private fun relationship(id2: UUID): MetadataRelationship = MetadataRelationship(
        metadataId1 = UUID.random(),
        metadataId2 = id2,
        relationship = "related"
    )

    private fun principalMock(principal: Principal = Principal(id = UUID.random())): Principal {
        val authenticated = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticated
        every { authenticated.asPrincipal() } returns principal
        return principal
    }

    // ---- guard branches ----------------------------------------------------

    @Test
    fun `throws when editor group check fails`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")

        assertFailsWith<IllegalStateException> {
            controller.resolveUnpublishedRelationships(authentication, id)
        }
        coVerify(exactly = 0) { metadataService.getById(id) }
    }

    @Test
    fun `throws when parent metadata not found`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns null

        val error = assertFailsWith<IllegalStateException> {
            controller.resolveUnpublishedRelationships(authentication, id)
        }
        assertTrue(error.message?.contains("Metadata not found") == true)
    }

    @Test
    fun `throws when parent permission denied`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } throws IllegalStateException("denied")

        assertFailsWith<IllegalStateException> {
            controller.resolveUnpublishedRelationships(authentication, id)
        }
    }

    @Test
    fun `throws when principal missing`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } returns Unit
        every { authentication.principal() } returns null

        val error = assertFailsWith<IllegalStateException> {
            controller.resolveUnpublishedRelationships(authentication, id)
        }
        assertTrue(error.message?.contains("missing principal") == true)
    }

    // ---- relationship loop -------------------------------------------------

    @Test
    fun `no relationships returns true without mutating`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        principalMock()
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } returns Unit
        coEvery { metadataService.getRelationships(id) } returns emptyList()

        val result = controller.resolveUnpublishedRelationships(authentication, id)

        assertTrue(result)
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `skips relationship when related metadata not found`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        val relatedId = UUID.random()
        principalMock()
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } returns Unit
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(relatedId))
        coEvery { metadataService.getById(relatedId) } returns null

        val result = controller.resolveUnpublishedRelationships(authentication, id)

        assertTrue(result)
        // The parent EDIT check still ran; the related one is skipped via `continue`.
        coVerify(exactly = 1) {
            permissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EDIT)
        }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `unpublished not-ready relationship is marked ready then transitioned and made fully public`() =
        runTest {
            val id = UUID.random()
            val parent = metadata(id)
            val relatedId = UUID.random()
            val principal = principalMock()
            val related = metadata(
                id = relatedId,
                workflowStateId = "draft",
                ready = null,
                public = false,
                publicContent = false,
                publicSupplementary = false,
            )
            val readied = related.copy(ready = OffsetDateTime.now())

            every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
            coEvery { metadataService.getById(id) } returns parent
            coEvery { metadataService.getById(relatedId) } returns related
            coEvery {
                permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
            } returns Unit
            coEvery {
                permissionEvaluator.verifyAllowed(authentication, related, PermissionAction.EDIT)
            } returns Unit
            coEvery { metadataService.getRelationships(id) } returns listOf(relationship(relatedId))
            coEvery { metadataService.setReady(related, principal) } returns readied
            coEvery { transitioner.beginTransition(authentication, any(), readied) } returns readied
            coEvery { metadataService.setPublic(readied, true) } returns Unit
            coEvery { metadataService.setPublicContent(readied, true) } returns Unit
            coEvery { metadataService.getSupplementary(readied.id) } returns
                listOf(mockk<MetadataSupplementary>())
            coEvery { metadataService.setPublicSupplementary(readied, true) } returns Unit

            val result = controller.resolveUnpublishedRelationships(authentication, id)

            assertTrue(result)
            coVerify { metadataService.setReady(related, principal) }
            val expected = BeginTransitionInput(
                metadataId = readied.id,
                version = readied.version,
                stateId = "published",
                status = "resolved by health check",
            )
            coVerify { transitioner.beginTransition(authentication, expected, readied) }
            coVerify { metadataService.setPublic(readied, true) }
            coVerify { metadataService.setPublicContent(readied, true) }
            coVerify { metadataService.setPublicSupplementary(readied, true) }
        }

    @Test
    fun `unpublished already-ready relationship transitions without marking ready`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        val relatedId = UUID.random()
        principalMock()
        val related = metadata(
            id = relatedId,
            workflowStateId = "draft",
            ready = OffsetDateTime.now(),
            public = true,
            publicContent = true,
            publicSupplementary = true,
        )

        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } returns Unit
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, related, PermissionAction.EDIT)
        } returns Unit
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(relatedId))
        coEvery { transitioner.beginTransition(authentication, any(), related) } returns related

        val result = controller.resolveUnpublishedRelationships(authentication, id)

        assertTrue(result)
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        coVerify { transitioner.beginTransition(authentication, any(), related) }
        // Already fully public: no visibility mutations.
        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `published relationship skips transition but still adjusts visibility`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        val relatedId = UUID.random()
        principalMock()
        val related = metadata(
            id = relatedId,
            workflowStateId = "published",
            ready = OffsetDateTime.now(),
            public = false,
            publicContent = false,
            publicSupplementary = false,
        )

        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } returns Unit
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, related, PermissionAction.EDIT)
        } returns Unit
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(relatedId))
        coEvery { metadataService.setPublic(related, true) } returns Unit
        coEvery { metadataService.setPublicContent(related, true) } returns Unit
        // No supplementary → the publicSupplementary branch is not taken.
        coEvery { metadataService.getSupplementary(related.id) } returns emptyList()

        val result = controller.resolveUnpublishedRelationships(authentication, id)

        assertTrue(result)
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
        coVerify { metadataService.setPublic(related, true) }
        coVerify { metadataService.setPublicContent(related, true) }
        coVerify { metadataService.getSupplementary(related.id) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `throws when related permission denied`() = runTest {
        val id = UUID.random()
        val parent = metadata(id)
        val relatedId = UUID.random()
        principalMock()
        val related = metadata(id = relatedId, workflowStateId = "published")

        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { metadataService.getById(id) } returns parent
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } returns Unit
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, related, PermissionAction.EDIT)
        } throws IllegalStateException("denied")
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(relatedId))

        assertFailsWith<IllegalStateException> {
            controller.resolveUnpublishedRelationships(authentication, id)
        }
    }
}
