package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantInput
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionParentCollection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionSupplementaryInput
import bosca.content.collection.model.CollectionWorkflowCompleteState
import bosca.content.collection.model.CollectionWorkflowState
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.OrderingInput
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.graphql.scalars.UploadedFile
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionMutationControllerCoverageTest {

    private val service = mockk<CollectionService>()
    private val permissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val slugService = mockk<SlugService>()
    private val json = Json

    private val controller = CollectionMutationController(
        service,
        permissionEvaluator,
        metadataService,
        metadataPermissionEvaluator,
        groupEvaluator,
        slugService,
        json
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

    private fun collection(
        id: UUID = UUID.random(),
        locked: Boolean = false,
        workflowStateId: String = "draft",
        public: Boolean = false,
        publicList: Boolean = false,
        publicSupplementary: Boolean = false
    ) = Collection(
        id = id,
        name = "test",
        languageTag = "en",
        workflowStateId = workflowStateId,
        locked = locked,
        public = public,
        publicList = publicList,
        publicSupplementary = publicSupplementary
    )

    private fun variant(id: UUID = UUID.random(), languageTag: String = "es") = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = "variant"
    )

    private fun supplementary(
        id: UUID = UUID.random(),
        collectionId: UUID = UUID.random()
    ) = CollectionSupplementary(
        id = id,
        collectionId = collectionId,
        key = "k",
        name = "n",
        created = java.time.OffsetDateTime.now(),
        modified = java.time.OffsetDateTime.now()
    )

    private fun principalMock(principal: Principal = Principal(id = UUID.random())): AuthenticatedPrincipal {
        val authenticated = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticated
        every { authenticated.asPrincipal() } returns principal
        return authenticated
    }

    private val attrs = JsonObject(mapOf("a" to JsonPrimitive(1)))

    // ---- add ---------------------------------------------------------------

    @Test
    fun `add with parent verifies parent permission and does not set ready`() = runTest {
        val parentId = UUID.random()
        val parent = collection(parentId)
        val input = CollectionInput(name = "c", languageTag = "en", parentCollectionId = parentId)
        val created = collection()

        coEvery { service.getById(parentId) } returns parent
        coEvery { permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT) } returns Unit
        coEvery { service.add(input, parent, attrs) } returns created

        val result = controller.add(authentication, input, attrs, false)

        assertEquals(created, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT) }
        coVerify(exactly = 0) { groupEvaluator.verifyHasEditorGroup(any()) }
        coVerify(exactly = 0) { service.setReady(any<Collection>(), any()) }
    }

    @Test
    fun `add without parent verifies editor group and sets ready`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val input = CollectionInput(name = "c", languageTag = "en")
        val created = collection()

        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.add(input, null, null) } returns created
        coEvery { service.setReady(created, principal) } returns Unit

        val result = controller.add(authentication, input, null, true)

        assertEquals(created, result)
        coVerify { service.setReady(created, principal) }
    }

    @Test
    fun `add setReady with no principal errors`() = runTest {
        every { authentication.principal() } returns null
        val input = CollectionInput(name = "c", languageTag = "en")
        val created = collection()

        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.add(input, null, null) } returns created

        assertFailsWith<IllegalStateException> {
            controller.add(authentication, input, null, true)
        }
    }

    // ---- setParentCollections ---------------------------------------------

    @Test
    fun `setParentCollections removes stale parents and adds or merges`() = runTest {
        val id = UUID.random()
        val staleParentId = UUID.random()
        val existingParentId = UUID.random()
        val newParentId = UUID.random()
        val col = collection(id)
        val staleParent = collection(staleParentId)
        val existingParent = collection(existingParentId)
        val newParent = collection(newParentId)

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EDIT) } returns Unit
        // current parents include stale (to remove) and existing (to merge)
        coEvery { service.getCollectionParents(id) } returns listOf(staleParent, existingParent)
        coEvery { service.getById(staleParentId) } returns staleParent
        coEvery { service.getById(existingParentId) } returns existingParent
        coEvery { service.getById(newParentId) } returns newParent
        coEvery { service.removeCollectionItem(staleParentId, id) } returns Unit
        coEvery { service.mergeCollectionItemAttributes(existingParentId, id, attrs) } returns Unit
        coEvery { service.addCollectionItem(newParentId, id, null) } returns Unit

        val result = controller.setParentCollections(
            authentication,
            id,
            listOf(
                CollectionParentCollection(existingParentId, attrs),
                CollectionParentCollection(newParentId, null)
            )
        )

        assertTrue(result)
        coVerify { service.removeCollectionItem(staleParentId, id) }
        coVerify { service.mergeCollectionItemAttributes(existingParentId, id, attrs) }
        coVerify { service.addCollectionItem(newParentId, id, null) }
    }

    @Test
    fun `setParentCollections existing parent with null attributes skips merge`() = runTest {
        val id = UUID.random()
        val existingParentId = UUID.random()
        val col = collection(id)
        val existingParent = collection(existingParentId)

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EDIT) } returns Unit
        coEvery { service.getCollectionParents(id) } returns listOf(existingParent)
        coEvery { service.getById(existingParentId) } returns existingParent

        val result = controller.setParentCollections(
            authentication,
            id,
            listOf(CollectionParentCollection(existingParentId, null))
        )

        assertTrue(result)
        coVerify(exactly = 0) { service.mergeCollectionItemAttributes(any(), any(), any()) }
        coVerify(exactly = 0) { service.addCollectionItem(any(), any(), any()) }
    }

    @Test
    fun `setParentCollections not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setParentCollections(authentication, id, emptyList())
        }
    }

    @Test
    fun `setParentCollections stale parent not found throws`() = runTest {
        val id = UUID.random()
        val staleId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getCollectionParents(id) } returns listOf(collection(staleId))
        coEvery { service.getById(staleId) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setParentCollections(authentication, id, emptyList())
        }
    }

    @Test
    fun `setParentCollections input parent not found throws`() = runTest {
        val id = UUID.random()
        val newId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getCollectionParents(id) } returns emptyList()
        coEvery { service.getById(newId) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setParentCollections(authentication, id, listOf(CollectionParentCollection(newId, null)))
        }
    }

    // ---- addLanguageVariant / editLanguageVariant / deleteLanguageVariant --

    @Test
    fun `addLanguageVariant sets ready`() = runTest {
        val id = UUID.random()
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val col = collection(id)
        val added = variant(id, "es")
        val input = CollectionLanguageVariantInput(id = id, languageTag = "es", name = "v")

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.addLanguageVariant(input) } returns added
        coEvery { service.setReady(added, principal) } returns Unit

        val result = controller.addLanguageVariant(authentication, input, true)
        assertEquals(col, result)
        coVerify { service.setReady(added, principal) }
    }

    @Test
    fun `addLanguageVariant without ready`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        val added = variant(id, "es")
        val input = CollectionLanguageVariantInput(id = id, languageTag = "es", name = "v")

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.addLanguageVariant(input) } returns added

        val result = controller.addLanguageVariant(authentication, input, null)
        assertEquals(col, result)
        coVerify(exactly = 0) { service.setReady(any<CollectionLanguageVariant>(), any()) }
    }

    @Test
    fun `addLanguageVariant not found throws`() = runTest {
        val id = UUID.random()
        val input = CollectionLanguageVariantInput(id = id, languageTag = "es", name = "v")
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.addLanguageVariant(authentication, input, false)
        }
    }

    @Test
    fun `editLanguageVariant happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        val input = CollectionLanguageVariantInput(id = id, languageTag = "es", name = "v")
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.editLanguageVariant(input) } returns variant(id, "es")

        val result = controller.editLanguageVariant(authentication, input)
        assertEquals(col, result)
        coVerify { service.editLanguageVariant(input) }
    }

    @Test
    fun `editLanguageVariant not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.editLanguageVariant(authentication, CollectionLanguageVariantInput(id = id, languageTag = "es", name = "v"))
        }
    }

    @Test
    fun `deleteLanguageVariant happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.DELETE) } returns Unit
        coEvery { service.deleteLanguageVariant(id, "es") } returns Unit

        val result = controller.deleteLanguageVariant(authentication, id, "es")
        assertEquals(col, result)
        coVerify { service.deleteLanguageVariant(id, "es") }
    }

    @Test
    fun `deleteLanguageVariant not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.deleteLanguageVariant(authentication, id, "es")
        }
    }

    // ---- addChildCollection / addChildMetadata -----------------------------

    @Test
    fun `addChildCollection happy path`() = runTest {
        val id = UUID.random()
        val childId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.addCollectionItem(id, childId, attrs) } returns Unit

        val result = controller.addChildCollection(authentication, id, childId, attrs)
        assertEquals(col, result)
    }

    @Test
    fun `addChildCollection not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.addChildCollection(authentication, id, UUID.random(), null)
        }
    }

    @Test
    fun `addChildMetadata happy path`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.addMetadataItem(id, metadataId, null) } returns Unit

        val result = controller.addChildMetadata(authentication, id, metadataId, null)
        assertEquals(col, result)
    }

    @Test
    fun `addChildMetadata not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.addChildMetadata(authentication, id, UUID.random(), null)
        }
    }

    // ---- setCollectionSearchable -------------------------------------------

    @Test
    fun `setCollectionSearchable happy path unlocked`() = runTest {
        val id = UUID.random()
        val col = collection(id, locked = false)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setSearchable(id, true) } returns Unit

        assertTrue(controller.setCollectionSearchable(authentication, id, true))
        coVerify { service.setSearchable(id, true) }
    }

    @Test
    fun `setCollectionSearchable locked but SA proceeds`() = runTest {
        val id = UUID.random()
        val col = collection(id, locked = true)
        coEvery { service.getById(id) } returns col
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setSearchable(id, false) } returns Unit

        assertTrue(controller.setCollectionSearchable(authentication, id, false))
    }

    @Test
    fun `setCollectionSearchable locked non-SA throws SecurityException`() = runTest {
        val id = UUID.random()
        val col = collection(id, locked = true)
        coEvery { service.getById(id) } returns col
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        assertFailsWith<SecurityException> {
            controller.setCollectionSearchable(authentication, id, true)
        }
    }

    @Test
    fun `setCollectionSearchable not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setCollectionSearchable(authentication, id, true)
        }
    }

    @Test
    fun `setCollectionRecommendable updates eligible collection and rejects locked or missing items`() = runTest {
        val id = UUID.random()
        val col = collection(id, locked = false)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setRecommendable(id, false) } returns Unit
        assertTrue(controller.setCollectionRecommendable(authentication, id, false))

        val lockedId = UUID.random()
        val locked = collection(lockedId, locked = true)
        coEvery { service.getById(lockedId) } returns locked
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { permissionEvaluator.verifyAllowed(authentication, locked, PermissionAction.EDIT) } returns Unit
        coEvery { service.setRecommendable(lockedId, true) } returns Unit
        assertTrue(controller.setCollectionRecommendable(authentication, lockedId, true))

        every { groupEvaluator.hasSaGroup(authentication) } returns false
        assertFailsWith<SecurityException> {
            controller.setCollectionRecommendable(authentication, lockedId, false)
        }

        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setCollectionRecommendable(authentication, missingId, false)
        }
    }

    @Test
    fun `collection eligibility mutations target a selected language variant`() = runTest {
        val id = UUID.random()
        val col = collection(id, locked = false)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setSearchable(id, false, "fr") } returns Unit
        coEvery { service.setRecommendable(id, false, "fr") } returns Unit

        assertTrue(controller.setCollectionSearchable(authentication, id, false, "fr"))
        assertTrue(controller.setCollectionRecommendable(authentication, id, false, "fr"))

        coVerify { service.setSearchable(id, false, "fr") }
        coVerify { service.setRecommendable(id, false, "fr") }
    }

    // ---- edit --------------------------------------------------------------

    @Test
    fun `edit happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        val edited = collection(id)
        val input = CollectionInput(name = "x", languageTag = "en")
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.edit(id, input) } returns edited

        assertEquals(edited, controller.edit(authentication, id, input))
    }

    @Test
    fun `edit not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.edit(authentication, id, CollectionInput(name = "x", languageTag = "en"))
        }
    }

    // ---- delete ------------------------------------------------------------

    @Test
    fun `delete non-recursive marks deleted`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.DELETE) } returns Unit
        coEvery { service.markDeleted(id) } returns Unit

        assertTrue(controller.delete(authentication, id, false))
        coVerify { service.markDeleted(id) }
    }

    @Test
    fun `delete recursive hits TODO`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.DELETE) } returns Unit

        assertFailsWith<NotImplementedError> {
            controller.delete(authentication, id, true)
        }
    }

    @Test
    fun `delete not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.delete(authentication, id, false)
        }
    }

    // ---- deleteAll ---------------------------------------------------------

    @Test
    fun `deleteAll marks allowed collections`() = runTest {
        val a = collection()
        val b = collection()
        val ids = listOf(a.id, b.id)
        coEvery { service.getByIds(ids) } returns listOf(a, b)
        coEvery { permissionEvaluator.filterAllowed(authentication, listOf(a, b), PermissionAction.DELETE) } returns listOf(a)
        coEvery { service.markDeleted(a.id) } returns Unit

        assertEquals(1, controller.deleteAll(authentication, ids))
        coVerify { service.markDeleted(a.id) }
        coVerify(exactly = 0) { service.markDeleted(b.id) }
    }

    @Test
    fun `deleteAll over limit throws`() = runTest {
        val ids = List(501) { UUID.random() }
        assertFailsWith<IllegalArgumentException> {
            controller.deleteAll(authentication, ids)
        }
    }

    // ---- setPublicAll ------------------------------------------------------

    @Test
    fun `setPublicAll as SA does not filter locked`() = runTest {
        val locked = collection(locked = true)
        val ids = listOf(locked.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { service.getByIds(ids) } returns listOf(locked)
        coEvery { permissionEvaluator.filterAllowed(authentication, listOf(locked), PermissionAction.EDIT) } returns listOf(locked)
        coEvery { service.setPublic(locked.id, true, null) } returns Unit
        coEvery { service.setPublicList(locked.id, false, null) } returns Unit
        coEvery { service.setPublicSupplementary(locked.id, true, null) } returns Unit
        coEvery { service.setSearchable(locked.id, false) } returns Unit

        assertEquals(1, controller.setPublicAll(authentication, ids, public = true, publicList = false, publicSupplementary = true, searchable = false))
        coVerify { service.setPublic(locked.id, true, null) }
        coVerify { service.setSearchable(locked.id, false) }
    }

    @Test
    fun `setPublicAll non-SA filters locked`() = runTest {
        val locked = collection(locked = true)
        val unlocked = collection(locked = false)
        val ids = listOf(locked.id, unlocked.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { service.getByIds(ids) } returns listOf(locked, unlocked)
        coEvery { permissionEvaluator.filterAllowed(authentication, listOf(unlocked), PermissionAction.EDIT) } returns listOf(unlocked)
        coEvery { service.setPublic(unlocked.id, true, null) } returns Unit
        coEvery { service.setPublicList(unlocked.id, true, null) } returns Unit
        coEvery { service.setPublicSupplementary(unlocked.id, true, null) } returns Unit
        coEvery { service.setSearchable(unlocked.id, true) } returns Unit

        assertEquals(1, controller.setPublicAll(authentication, ids, public = true, publicList = true, publicSupplementary = true, searchable = true))
    }

    @Test
    fun `setPublicAll over limit throws`() = runTest {
        val ids = List(501) { UUID.random() }
        assertFailsWith<IllegalArgumentException> {
            controller.setPublicAll(authentication, ids, public = true, publicList = true, publicSupplementary = true, searchable = true)
        }
    }

    @Test
    fun `setRecommendableAll filters locked collections and enforces the bulk limit`() = runTest {
        val locked = collection(locked = true)
        val unlocked = collection(locked = false)
        val ids = listOf(locked.id, unlocked.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { service.getByIds(ids) } returns listOf(locked, unlocked)
        coEvery {
            permissionEvaluator.filterAllowed(authentication, listOf(unlocked), PermissionAction.EDIT)
        } returns listOf(unlocked)
        coEvery { service.setRecommendable(unlocked.id, false) } returns Unit

        assertEquals(1, controller.setRecommendableAll(authentication, ids, false))
        coVerify(exactly = 1) { service.setRecommendable(unlocked.id, false) }
        coVerify(exactly = 0) { service.setRecommendable(locked.id, any()) }
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery {
            permissionEvaluator.filterAllowed(authentication, listOf(locked, unlocked), PermissionAction.EDIT)
        } returns listOf(locked, unlocked)
        coEvery { service.setRecommendable(any(), true) } returns Unit
        assertEquals(2, controller.setRecommendableAll(authentication, ids, true))

        assertFailsWith<IllegalArgumentException> {
            controller.setRecommendableAll(authentication, List(501) { UUID.random() }, true)
        }
    }

    // ---- setWorkflowStateAll -----------------------------------------------

    @Test
    fun `setWorkflowStateAll transitions candidates`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val a = collection(workflowStateId = "draft")
        val b = collection(workflowStateId = "published")
        val ids = listOf(a.id, b.id)
        coEvery { service.getByIds(ids) } returns listOf(a, b)
        coEvery { permissionEvaluator.filterAllowed(authentication, listOf(a), PermissionAction.EXECUTE) } returns listOf(a)
        coEvery {
            service.setState(principal = principal, item = a, toStateId = "published", status = "s")
        } returns a

        assertEquals(1, controller.setWorkflowStateAll(authentication, ids, "published", "s"))
        coVerify { service.setState(principal = principal, item = a, toStateId = "published", status = "s") }
    }

    @Test
    fun `setWorkflowStateAll no principal returns zero`() = runTest {
        every { authentication.principal() } returns null
        assertEquals(0, controller.setWorkflowStateAll(authentication, listOf(UUID.random()), "s1", "s"))
    }

    @Test
    fun `setWorkflowStateAll over limit throws`() = runTest {
        val ids = List(501) { UUID.random() }
        assertFailsWith<IllegalArgumentException> {
            controller.setWorkflowStateAll(authentication, ids, "s", "st")
        }
    }

    // ---- addMetadataRelationship / edit / delete / merge -------------------

    @Test
    fun `addMetadataRelationship happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        val input = CollectionMetadataRelationshipInput(id = id, metadataId = UUID.random(), relationship = "r")
        val relationship = CollectionMetadataRelationship(id, input.metadataId, "r")
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.addMetadataRelationship(input) } returns relationship

        assertEquals(relationship, controller.addMetadataRelationship(authentication, input))
    }

    @Test
    fun `addMetadataRelationship not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.addMetadataRelationship(authentication, CollectionMetadataRelationshipInput(id = id, metadataId = UUID.random()))
        }
    }

    @Test
    fun `editMetadataRelationship happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        val input = CollectionMetadataRelationshipInput(id = id, metadataId = UUID.random(), relationship = "r")
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.editMetadataRelationship(input) } returns CollectionMetadataRelationship(id, input.metadataId, "r")

        assertTrue(controller.editMetadataRelationship(authentication, input))
    }

    @Test
    fun `editMetadataRelationship not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.editMetadataRelationship(authentication, CollectionMetadataRelationshipInput(id = id, metadataId = UUID.random())))
    }

    @Test
    fun `deleteMetadataRelationship with languageTag`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.deleteMetadataRelationship(id, "es", metadataId, "r") } returns Unit

        assertTrue(controller.deleteMetadataRelationship(authentication, id, metadataId, "r", "es"))
        coVerify { service.deleteMetadataRelationship(id, "es", metadataId, "r") }
    }

    @Test
    fun `deleteMetadataRelationship without languageTag`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.deleteMetadataRelationship(id, metadataId, "r") } returns Unit

        assertTrue(controller.deleteMetadataRelationship(authentication, id, metadataId, "r", null))
        coVerify { service.deleteMetadataRelationship(id, metadataId, "r") }
    }

    @Test
    fun `deleteMetadataRelationship not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.deleteMetadataRelationship(authentication, id, UUID.random(), "r"))
    }

    @Test
    fun `mergeMetadataRelationshipAttributes with languageTag`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.mergeMetadataRelationshipAttributes(id, "es", metadataId, "r", attrs) } returns CollectionMetadataRelationship(id, metadataId, "r")

        assertTrue(controller.mergeMetadataRelationshipAttributes(authentication, attrs, id, metadataId, "r", "es"))
    }

    @Test
    fun `mergeMetadataRelationshipAttributes without languageTag`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.mergeMetadataRelationshipAttributes(id, metadataId, "r", attrs) } returns CollectionMetadataRelationship(id, metadataId, "r")

        assertTrue(controller.mergeMetadataRelationshipAttributes(authentication, attrs, id, metadataId, "r", null))
    }

    @Test
    fun `mergeMetadataRelationshipAttributes not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.mergeMetadataRelationshipAttributes(authentication, attrs, id, UUID.random(), "r"))
    }

    // ---- attribute management ----------------------------------------------

    @Test
    fun `setCollectionAttributes happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setAttributes(id, attrs) } returns Unit
        assertTrue(controller.setCollectionAttributes(authentication, id, attrs))
    }

    @Test
    fun `setCollectionAttributes not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.setCollectionAttributes(authentication, id, attrs))
    }

    @Test
    fun `mergeCollectionAttributes happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.mergeAttributes(id, attrs) } returns Unit
        assertTrue(controller.mergeCollectionAttributes(authentication, id, attrs))
    }

    @Test
    fun `mergeCollectionAttributes not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.mergeCollectionAttributes(authentication, id, attrs))
    }

    @Test
    fun `setCollectionSystemAttributes happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setSystemAttributes(id, attrs) } returns Unit
        assertTrue(controller.setCollectionSystemAttributes(authentication, id, attrs))
    }

    @Test
    fun `setCollectionSystemAttributes not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.setCollectionSystemAttributes(authentication, id, attrs))
    }

    @Test
    fun `mergeCollectionItemAttributes happy path`() = runTest {
        val id = UUID.random()
        val itemId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.mergeCollectionItemAttributes(id, itemId, attrs) } returns Unit
        assertTrue(controller.mergeCollectionItemAttributes(authentication, attrs, id, itemId))
    }

    @Test
    fun `mergeCollectionItemAttributes not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.mergeCollectionItemAttributes(authentication, attrs, id, UUID.random()))
    }

    @Test
    fun `mergeMetadataItemAttributes happy path`() = runTest {
        val id = UUID.random()
        val itemId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.mergeMetadataItemAttributes(id, itemId, attrs) } returns Unit
        assertTrue(controller.mergeMetadataItemAttributes(authentication, attrs, id, itemId))
    }

    @Test
    fun `mergeMetadataItemAttributes not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.mergeMetadataItemAttributes(authentication, attrs, id, UUID.random()))
    }

    // ---- setLocked ---------------------------------------------------------

    @Test
    fun `setLocked happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setLocked(col, true) } returns Unit

        val result = controller.setLocked(authentication, id, true)
        assertEquals(true, result?.locked)
    }

    @Test
    fun `setLocked not found returns null`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertNull(controller.setLocked(authentication, id, true))
    }

    // ---- setPublic / setPublicList / setPublicSupplementary not found ------

    @Test
    fun `setPublic not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> { controller.setPublic(authentication, id, true) }
    }

    @Test
    fun `setPublic variant not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns collection(id)
        coEvery { service.getLanguageVariant(id, "es") } returns null
        assertFailsWith<NoSuchElementException> { controller.setPublic(authentication, id, true, "es") }
    }

    @Test
    fun `setPublicList not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> { controller.setPublicList(authentication, id, true) }
    }

    @Test
    fun `setPublicList variant not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns collection(id)
        coEvery { service.getLanguageVariant(id, "es") } returns null
        assertFailsWith<NoSuchElementException> { controller.setPublicList(authentication, id, true, "es") }
    }

    @Test
    fun `setPublicSupplementary not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> { controller.setPublicSupplementary(authentication, id, true) }
    }

    @Test
    fun `setPublicSupplementary variant not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns collection(id)
        coEvery { service.getLanguageVariant(id, "es") } returns null
        assertFailsWith<NoSuchElementException> { controller.setPublicSupplementary(authentication, id, true, "es") }
    }

    // ---- setNotReady / setReady --------------------------------------------

    @Test
    fun `setNotReady without languageTag`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setNotReady(col) } returns Unit
        assertTrue(controller.setNotReady(authentication, id, null))
    }

    @Test
    fun `setNotReady with languageTag`() = runTest {
        val id = UUID.random()
        val v = variant(id, "es")
        coEvery { service.getLanguageVariant(id, "es") } returns v
        coEvery { permissionEvaluator.verifyAllowed(authentication, v, PermissionAction.EDIT) } returns Unit
        coEvery { service.setNotReady(v) } returns Unit
        assertTrue(controller.setNotReady(authentication, id, "es"))
    }

    @Test
    fun `setNotReady collection missing returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.setNotReady(authentication, id, null))
    }

    @Test
    fun `setNotReady variant missing returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getLanguageVariant(id, "es") } returns null
        assertFalse(controller.setNotReady(authentication, id, "es"))
    }

    @Test
    fun `setReady without languageTag`() = runTest {
        val id = UUID.random()
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setReady(id, principal, null) } returns Unit
        assertTrue(controller.setReady(authentication, id, null))
    }

    @Test
    fun `setReady with languageTag`() = runTest {
        val id = UUID.random()
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val v = variant(id, "es")
        coEvery { service.getLanguageVariant(id, "es") } returns v
        coEvery { permissionEvaluator.verifyAllowed(authentication, v, PermissionAction.EDIT) } returns Unit
        coEvery { service.setReady(id, principal, "es") } returns Unit
        assertTrue(controller.setReady(authentication, id, "es"))
    }

    @Test
    fun `setReady collection missing returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.setReady(authentication, id, null))
    }

    @Test
    fun `setReady variant missing returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getLanguageVariant(id, "es") } returns null
        assertFalse(controller.setReady(authentication, id, "es"))
    }

    @Test
    fun `setReady missing principal errors`() = runTest {
        val id = UUID.random()
        every { authentication.principal() } returns null
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        assertFailsWith<IllegalStateException> { controller.setReady(authentication, id, null) }
    }

    // ---- setReadyAll -------------------------------------------------------

    @Test
    fun `setReadyAll as SA marks all allowed`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val locked = collection(locked = true)
        val ids = listOf(locked.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { service.getByIds(ids) } returns listOf(locked)
        coEvery { permissionEvaluator.filterAllowed(authentication, listOf(locked), PermissionAction.EDIT) } returns listOf(locked)
        coEvery { service.setReady(locked.id, principal, null) } returns Unit

        assertEquals(1, controller.setReadyAll(authentication, ids))
        coVerify { service.setReady(locked.id, principal, null) }
    }

    @Test
    fun `setReadyAll non-SA filters locked`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val locked = collection(locked = true)
        val unlocked = collection(locked = false)
        val ids = listOf(locked.id, unlocked.id)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { service.getByIds(ids) } returns listOf(locked, unlocked)
        coEvery { permissionEvaluator.filterAllowed(authentication, listOf(unlocked), PermissionAction.EDIT) } returns listOf(unlocked)
        coEvery { service.setReady(unlocked.id, principal, null) } returns Unit

        assertEquals(1, controller.setReadyAll(authentication, ids))
    }

    @Test
    fun `setReadyAll missing principal errors`() = runTest {
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        every { authentication.principal() } returns null
        assertFailsWith<IllegalStateException> {
            controller.setReadyAll(authentication, listOf(UUID.random()))
        }
    }

    @Test
    fun `setReadyAll over limit throws`() = runTest {
        val ids = List(501) { UUID.random() }
        assertFailsWith<IllegalArgumentException> {
            controller.setReadyAll(authentication, ids)
        }
    }

    // ---- setCollectionSlug -------------------------------------------------

    @Test
    fun `setCollectionSlug happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { slugService.deleteCollectionSlug(id, null) } returns Unit
        coEvery { slugService.get("my-slug") } returns null
        val slug = Slug(slug = "my-slug", collectionId = id, languageTag = null)
        coEvery { slugService.add(slug) } returns slug

        assertEquals("my-slug", controller.setCollectionSlug(authentication, id, "my-slug", null))
    }

    @Test
    fun `setCollectionSlug already exists errors`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { slugService.deleteCollectionSlug(id, null) } returns Unit
        coEvery { slugService.get("dup") } returns Slug(slug = "dup")

        assertFailsWith<IllegalStateException> {
            controller.setCollectionSlug(authentication, id, "dup", null)
        }
    }

    @Test
    fun `setCollectionSlug not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setCollectionSlug(authentication, id, "s", null)
        }
    }

    // ---- addPermission / deletePermission ----------------------------------

    @Test
    fun `addPermission happy path`() = runTest {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val col = collection(entityId)
        val input = PermissionInput(action = PermissionAction.VIEW, entityId = entityId, groupId = groupId)
        coEvery { service.getById(entityId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.MANAGE) } returns Unit
        coEvery { service.addPermission(entityId, groupId, PermissionAction.VIEW) } returns mockk()

        val result = controller.addPermission(authentication, input)
        assertEquals(Permission(groupId = groupId, action = PermissionAction.VIEW), result)
    }

    @Test
    fun `addPermission not found throws`() = runTest {
        val entityId = UUID.random()
        coEvery { service.getById(entityId) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.addPermission(authentication, PermissionInput(action = PermissionAction.VIEW, entityId = entityId, groupId = UUID.random()))
        }
    }

    @Test
    fun `deletePermission happy path`() = runTest {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val col = collection(entityId)
        val input = PermissionInput(action = PermissionAction.EDIT, entityId = entityId, groupId = groupId)
        coEvery { service.getById(entityId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.MANAGE) } returns Unit
        coEvery { service.deletePermission(entityId, groupId, PermissionAction.EDIT) } returns Unit

        val result = controller.deletePermission(authentication, input)
        assertEquals(Permission(groupId = groupId, action = PermissionAction.EDIT), result)
    }

    @Test
    fun `deletePermission not found throws`() = runTest {
        val entityId = UUID.random()
        coEvery { service.getById(entityId) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.deletePermission(authentication, PermissionInput(action = PermissionAction.EDIT, entityId = entityId, groupId = UUID.random()))
        }
    }

    // ---- supplementary -----------------------------------------------------

    @Test
    fun `addSupplementary happy path`() = runTest {
        val collectionId = UUID.random()
        val col = collection(collectionId)
        val input = CollectionSupplementaryInput(collectionId = collectionId, key = "k", name = "n", contentType = "text/plain")
        val created = supplementary(collectionId = collectionId)
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.addSupplementary(input) } returns created

        val result = controller.addSupplementary(authentication, input)
        assertEquals(col, result.collection)
        assertEquals(created, result.supplementary)
    }

    @Test
    fun `addSupplementary not found throws`() = runTest {
        val collectionId = UUID.random()
        coEvery { service.getById(collectionId) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.addSupplementary(authentication, CollectionSupplementaryInput(collectionId = collectionId, key = "k", name = "n", contentType = "t"))
        }
    }

    @Test
    fun `deleteSupplementary happy path`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        val sup = supplementary(supId, collectionId)
        val col = collection(collectionId)
        coEvery { service.getSupplementaryById(supId) } returns sup
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.deleteSupplementary(col, supId) } returns Unit

        assertTrue(controller.deleteSupplementary(authentication, supId))
    }

    @Test
    fun `deleteSupplementary supplementary not found throws`() = runTest {
        val supId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns null
        assertFailsWith<NoSuchElementException> { controller.deleteSupplementary(authentication, supId) }
    }

    @Test
    fun `deleteSupplementary collection not found throws`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns supplementary(supId, collectionId)
        coEvery { service.getById(collectionId) } returns null
        assertFailsWith<NoSuchElementException> { controller.deleteSupplementary(authentication, supId) }
    }

    @Test
    fun `setSupplementaryContents happy path`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val sup = supplementary(supId, collectionId)
        val col = collection(collectionId)
        val file = UploadedFile(name = "f.txt", contentType = "text/plain", inputStream = ByteArrayInputStream(ByteArray(0)))
        coEvery { service.getSupplementaryById(supId) } returns sup
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery {
            service.updateSupplementaryContent(principal, col, supId, any<bosca.server.content.PartData.FileItem>(), "text/plain")
        } returns Unit

        assertTrue(controller.setSupplementaryContents(authentication, "text/plain", file, supId))
        coVerify { service.updateSupplementaryContent(principal, col, supId, any<bosca.server.content.PartData.FileItem>(), "text/plain") }
    }

    @Test
    fun `setSupplementaryContents no principal returns false`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        every { authentication.principal() } returns null
        val sup = supplementary(supId, collectionId)
        val col = collection(collectionId)
        val file = UploadedFile(name = "f.txt", contentType = "text/plain", inputStream = ByteArrayInputStream(ByteArray(0)))
        coEvery { service.getSupplementaryById(supId) } returns sup
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit

        assertFalse(controller.setSupplementaryContents(authentication, "text/plain", file, supId))
    }

    @Test
    fun `setSupplementaryContents supplementary not found throws`() = runTest {
        val supId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns null
        val file = UploadedFile(name = "f", contentType = "t", inputStream = ByteArrayInputStream(ByteArray(0)))
        assertFailsWith<NoSuchElementException> { controller.setSupplementaryContents(authentication, "t", file, supId) }
    }

    @Test
    fun `setSupplementaryContents collection not found throws`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns supplementary(supId, collectionId)
        coEvery { service.getById(collectionId) } returns null
        val file = UploadedFile(name = "f", contentType = "t", inputStream = ByteArrayInputStream(ByteArray(0)))
        assertFailsWith<NoSuchElementException> { controller.setSupplementaryContents(authentication, "t", file, supId) }
    }

    @Test
    fun `setSupplementaryTextContents happy path`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val sup = supplementary(supId, collectionId)
        val col = collection(collectionId)
        coEvery { service.getSupplementaryById(supId) } returns sup
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.updateSupplementaryContent(principal, col, supId, "content", "text/plain") } returns Unit

        assertTrue(controller.setSupplementaryTextContents(authentication, supId, "content", "text/plain"))
    }

    @Test
    fun `setSupplementaryTextContents no principal returns false`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        every { authentication.principal() } returns null
        coEvery { service.getSupplementaryById(supId) } returns supplementary(supId, collectionId)
        val col = collection(collectionId)
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        assertFalse(controller.setSupplementaryTextContents(authentication, supId, "c", "t"))
    }

    @Test
    fun `setSupplementaryTextContents supplementary not found throws`() = runTest {
        val supId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns null
        assertFailsWith<NoSuchElementException> { controller.setSupplementaryTextContents(authentication, supId, "c", "t") }
    }

    @Test
    fun `setSupplementaryTextContents collection not found throws`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns supplementary(supId, collectionId)
        coEvery { service.getById(collectionId) } returns null
        assertFailsWith<NoSuchElementException> { controller.setSupplementaryTextContents(authentication, supId, "c", "t") }
    }

    @Test
    fun `setSupplementaryUploaded happy path`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        val sup = supplementary(supId, collectionId)
        val col = collection(collectionId)
        coEvery { service.getSupplementaryById(supId) } returns sup
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.markSupplementaryUploaded(supId, "text/plain", 10) } returns Unit

        assertTrue(controller.setSupplementaryUploaded(authentication, supId, "text/plain", 10))
    }

    @Test
    fun `setSupplementaryUploaded supplementary not found throws`() = runTest {
        val supId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns null
        assertFailsWith<NoSuchElementException> { controller.setSupplementaryUploaded(authentication, supId, "t", 1) }
    }

    @Test
    fun `setSupplementaryUploaded collection not found throws`() = runTest {
        val supId = UUID.random()
        val collectionId = UUID.random()
        coEvery { service.getSupplementaryById(supId) } returns supplementary(supId, collectionId)
        coEvery { service.getById(collectionId) } returns null
        assertFailsWith<NoSuchElementException> { controller.setSupplementaryUploaded(authentication, supId, "t", 1) }
    }

    // ---- removeChildCollection / removeChildMetadata -----------------------

    @Test
    fun `removeChildCollection happy path`() = runTest {
        val id = UUID.random()
        val childId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.removeCollectionItem(id, childId) } returns Unit
        assertEquals(col, controller.removeChildCollection(authentication, id, childId))
    }

    @Test
    fun `removeChildCollection not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> { controller.removeChildCollection(authentication, id, UUID.random()) }
    }

    @Test
    fun `removeChildMetadata happy path`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.removeMetadataItem(id, metadataId) } returns Unit
        assertEquals(col, controller.removeChildMetadata(authentication, id, metadataId))
    }

    @Test
    fun `removeChildMetadata not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> { controller.removeChildMetadata(authentication, id, UUID.random()) }
    }

    // ---- permanentlyDelete -------------------------------------------------

    @Test
    fun `permanentlyDelete happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.DELETE) } returns Unit
        coEvery { service.permanentlyDelete(id) } returns Unit
        assertTrue(controller.permanentlyDelete(authentication, id))
    }

    @Test
    fun `permanentlyDelete not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.permanentlyDelete(authentication, id))
    }

    // ---- setChildItemAttributes --------------------------------------------

    @Test
    fun `setChildItemAttributes with both ids`() = runTest {
        val id = UUID.random()
        val childCollectionId = UUID.random()
        val childMetadataId = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setCollectionItemAttributes(id, childCollectionId, attrs) } returns col
        coEvery { service.setMetadataItemAttributes(id, childMetadataId, attrs) } returns col

        val result = controller.setChildItemAttributes(authentication, id, attrs, childCollectionId, childMetadataId)
        assertEquals(col, result)
        coVerify { service.setCollectionItemAttributes(id, childCollectionId, attrs) }
        coVerify { service.setMetadataItemAttributes(id, childMetadataId, attrs) }
    }

    @Test
    fun `setChildItemAttributes with neither id`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit

        val result = controller.setChildItemAttributes(authentication, id, null, null, null)
        assertEquals(col, result)
        coVerify(exactly = 0) { service.setCollectionItemAttributes(any(), any(), any()) }
        coVerify(exactly = 0) { service.setMetadataItemAttributes(any(), any(), any()) }
    }

    @Test
    fun `setChildItemAttributes not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setChildItemAttributes(authentication, id, null, null, null)
        }
    }

    // ---- setCategories / setTemplate / setCollectionOrdering ---------------

    @Test
    fun `setCategories happy path`() = runTest {
        val id = UUID.random()
        val categoryIds = listOf(UUID.random())
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setCategories(id, categoryIds) } returns Unit
        assertTrue(controller.setCategories(authentication, id, categoryIds))
    }

    @Test
    fun `setCategories not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.setCategories(authentication, id, emptyList()))
    }

    @Test
    fun `setTemplate happy path`() = runTest {
        val collectionId = UUID.random()
        val templateId = UUID.random()
        val col = collection(collectionId)
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setTemplate(collectionId, templateId, 2) } returns Unit
        assertTrue(controller.setTemplate(authentication, collectionId, templateId, 2))
    }

    @Test
    fun `setTemplate not found returns false`() = runTest {
        val collectionId = UUID.random()
        coEvery { service.getById(collectionId) } returns null
        assertFalse(controller.setTemplate(authentication, collectionId, UUID.random(), 1))
    }

    @Test
    fun `setCollectionOrdering happy path`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.setCollectionOrdering(id, any()) } returns Unit
        assertTrue(controller.setCollectionOrdering(authentication, id, listOf(OrderingInput(field = "name"))))
        coVerify { service.setCollectionOrdering(id, any()) }
    }

    @Test
    fun `setCollectionOrdering not found returns false`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFalse(controller.setCollectionOrdering(authentication, id, emptyList()))
    }

    // ---- setWorkflowState / setWorkflowStateComplete -----------------------

    @Test
    fun `setWorkflowState immediate`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val collectionId = UUID.random()
        val col = collection(collectionId)
        val state = CollectionWorkflowState(collectionId = collectionId, stateId = "published", status = "s", immediate = true)
        coEvery { service.getById(collectionId) } returns col
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { service.setState(principal = principal, item = col, toStateId = "published", status = "s") } returns col

        assertTrue(controller.setWorkflowState(authentication, state))
        coVerify { service.setState(principal = principal, item = col, toStateId = "published", status = "s") }
    }

    @Test
    fun `setWorkflowState pending`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val collectionId = UUID.random()
        val col = collection(collectionId)
        val state = CollectionWorkflowState(collectionId = collectionId, stateId = "published", status = "s", immediate = false)
        coEvery { service.getById(collectionId) } returns col
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { service.setPendingState(principal = principal, item = col, toStateId = "published", status = "s") } returns col

        assertTrue(controller.setWorkflowState(authentication, state))
        coVerify { service.setPendingState(principal = principal, item = col, toStateId = "published", status = "s") }
    }

    @Test
    fun `setWorkflowState no principal returns false`() = runTest {
        every { authentication.principal() } returns null
        val state = CollectionWorkflowState(collectionId = UUID.random(), stateId = "s", status = "st", immediate = true)
        assertFalse(controller.setWorkflowState(authentication, state))
    }

    @Test
    fun `setWorkflowState collection missing returns false`() = runTest {
        principalMock()
        val collectionId = UUID.random()
        val state = CollectionWorkflowState(collectionId = collectionId, stateId = "s", status = "st", immediate = true)
        coEvery { service.getById(collectionId) } returns null
        assertFalse(controller.setWorkflowState(authentication, state))
    }

    @Test
    fun `setWorkflowStateComplete happy path`() = runTest {
        val principal = Principal(id = UUID.random())
        principalMock(principal)
        val collectionId = UUID.random()
        val col = collection(collectionId)
        val state = CollectionWorkflowCompleteState(collectionId = collectionId, status = "done")
        coEvery { service.getById(collectionId) } returns col
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { service.setPendingStateComplete(item = col, status = "done", principal = principal) } returns col

        assertTrue(controller.setWorkflowStateComplete(authentication, state))
        coVerify { service.setPendingStateComplete(item = col, status = "done", principal = principal) }
    }

    @Test
    fun `setWorkflowStateComplete no principal returns false`() = runTest {
        every { authentication.principal() } returns null
        assertFalse(controller.setWorkflowStateComplete(authentication, CollectionWorkflowCompleteState(collectionId = UUID.random(), status = "d")))
    }

    @Test
    fun `setWorkflowStateComplete collection missing returns false`() = runTest {
        principalMock()
        val collectionId = UUID.random()
        coEvery { service.getById(collectionId) } returns null
        assertFalse(controller.setWorkflowStateComplete(authentication, CollectionWorkflowCompleteState(collectionId = collectionId, status = "d")))
    }

    // ---- setMetadataRelationships ------------------------------------------

    @Test
    fun `setMetadataRelationships without languageTag deletes stale and adds new`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val staleMetadataId = UUID.random()
        val col = collection(id)
        val stale = CollectionMetadataRelationship(id, staleMetadataId, "old")
        val newInput = CollectionMetadataRelationshipInput(id = id, metadataId = metadataId, relationship = "new")

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getMetadataRelationships(id) } returns listOf(stale)
        coEvery { service.deleteMetadataRelationship(id, staleMetadataId, "old") } returns Unit
        coEvery { service.addMetadataRelationship(newInput.copy(languageTag = null)) } returns CollectionMetadataRelationship(id, metadataId, "new")

        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(newInput), null))
        coVerify { service.deleteMetadataRelationship(id, staleMetadataId, "old") }
        coVerify { service.addMetadataRelationship(newInput.copy(languageTag = null)) }
    }

    @Test
    fun `setMetadataRelationships with languageTag deletes stale and adds new`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val staleMetadataId = UUID.random()
        val col = collection(id)
        val stale = CollectionLanguageVariantMetadataRelationship(id, staleMetadataId, "es", "old")
        val newInput = CollectionMetadataRelationshipInput(id = id, metadataId = metadataId, relationship = "new")

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getMetadataRelationships(id, "es") } returns listOf(stale)
        coEvery { service.deleteMetadataRelationship(id, "es", staleMetadataId, "old") } returns Unit
        coEvery { service.addMetadataRelationship(newInput.copy(languageTag = "es")) } returns CollectionMetadataRelationship(id, metadataId, "new")

        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(newInput), "es"))
        coVerify { service.deleteMetadataRelationship(id, "es", staleMetadataId, "old") }
        coVerify { service.addMetadataRelationship(newInput.copy(languageTag = "es")) }
    }

    @Test
    fun `setMetadataRelationships merges existing with languageTag`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        val existing = CollectionLanguageVariantMetadataRelationship(id, metadataId, "es", "r")
        val input = CollectionMetadataRelationshipInput(id = id, metadataId = metadataId, relationship = "r", attributes = attrs)

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getMetadataRelationships(id, "es") } returns listOf(existing)
        coEvery { service.mergeMetadataRelationshipAttributes(id, "es", metadataId, "r", attrs) } returns existing

        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(input), "es"))
        coVerify { service.mergeMetadataRelationshipAttributes(id, "es", metadataId, "r", attrs) }
    }

    @Test
    fun `setMetadataRelationships merges existing empty relationship no languageTag`() = runTest {
        // input.relationship is a non-null empty string that matches the existing empty-string
        // relationship, so the key is found in oldMap and the merge branch (left side of ?: "") runs.
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        val existing = CollectionMetadataRelationship(id, metadataId, "")
        val input = CollectionMetadataRelationshipInput(id = id, metadataId = metadataId, relationship = "", attributes = attrs)

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getMetadataRelationships(id) } returns listOf(existing)
        coEvery { service.mergeMetadataRelationshipAttributes(id, metadataId, "", attrs) } returns existing

        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(input), null))
        coVerify { service.mergeMetadataRelationshipAttributes(id, metadataId, "", attrs) }
    }

    @Test
    fun `setMetadataRelationships existing with null attributes skips merge`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val col = collection(id)
        val existing = CollectionMetadataRelationship(id, metadataId, "r")
        val input = CollectionMetadataRelationshipInput(id = id, metadataId = metadataId, relationship = "r", attributes = null)

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getMetadataRelationships(id) } returns listOf(existing)

        assertTrue(controller.setMetadataRelationships(authentication, id, listOf(input), null))
        coVerify(exactly = 0) { service.mergeMetadataRelationshipAttributes(any(), any<UUID>(), any(), any()) }
    }

    @Test
    fun `setMetadataRelationships mismatched id throws`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        val input = CollectionMetadataRelationshipInput(id = UUID.random(), metadataId = UUID.random(), relationship = "r")

        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.getMetadataRelationships(id) } returns emptyList()

        assertFailsWith<IllegalArgumentException> {
            controller.setMetadataRelationships(authentication, id, listOf(input), null)
        }
    }

    @Test
    fun `setMetadataRelationships not found throws`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            controller.setMetadataRelationships(authentication, id, emptyList(), null)
        }
    }

    // ---- syncVariants ------------------------------------------------------

    @Test
    fun `syncVariants happy path`() = runTest {
        val collectionId = UUID.random()
        val col = collection(collectionId)
        coEvery { service.getById(collectionId) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } returns Unit
        coEvery { service.syncVariantItems(collectionId) } returns Unit
        assertTrue(controller.syncVariants(authentication, collectionId))
    }

    @Test
    fun `syncVariants not found throws`() = runTest {
        val collectionId = UUID.random()
        coEvery { service.getById(collectionId) } returns null
        assertFailsWith<NoSuchElementException> { controller.syncVariants(authentication, collectionId) }
    }

    // ---- permission-denied propagation -------------------------------------

    @Test
    fun `edit propagates permission denied`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { service.getById(id) } returns col
        coEvery { permissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT) } throws SecurityException("denied")
        assertFailsWith<SecurityException> {
            controller.edit(authentication, id, CollectionInput(name = "x", languageTag = "en"))
        }
    }

    @Test
    fun `add propagates editor group denied`() = runTest {
        val input = CollectionInput(name = "c", languageTag = "en")
        coEvery { groupEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("denied")
        assertFailsWith<SecurityException> {
            controller.add(authentication, input, null, false)
        }
    }
}
