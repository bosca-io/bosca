package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindQueryInput
import bosca.content.metadata.graphql.CollectionTemplates
import bosca.content.security.CollectionPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CollectionsControllerCoverageTest {

    private val service = mockk<CollectionService>()
    private val permissions = mockk<CollectionPermissionEvaluator>()

    private val controller = CollectionsController(service, permissions)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createCollection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "Test Collection",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "published"
    )

    @Test
    fun `templates returns CollectionTemplates`() {
        assertSame(CollectionTemplates, controller.templates())
    }

    @Test
    fun `find keeps only allowed collections`() = runTest {
        val query = FindQueryInput()
        val allowed = createCollection()
        val denied = createCollection()

        coEvery { service.find(query) } returns listOf(allowed, denied)
        coEvery { permissions.isAllowed(authentication, allowed, PermissionAction.VIEW) } returns true
        coEvery { permissions.isAllowed(authentication, denied, PermissionAction.VIEW) } returns false

        val result = controller.find(authentication, query)

        assertEquals(listOf(allowed), result)
    }

    @Test
    fun `find with null authentication and no allowed collections returns empty`() = runTest {
        val query = FindQueryInput()
        val denied = createCollection()

        coEvery { service.find(query) } returns listOf(denied)
        coEvery { permissions.isAllowed(null, denied, PermissionAction.VIEW) } returns false

        val result = controller.find(null, query)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findBySystem keeps only allowed collections`() = runTest {
        val query = FindQueryInput()
        val allowed = createCollection()
        val denied = createCollection()

        coEvery { service.findBySystem(query) } returns listOf(allowed, denied)
        coEvery { permissions.isAllowed(authentication, allowed, PermissionAction.VIEW) } returns true
        coEvery { permissions.isAllowed(authentication, denied, PermissionAction.VIEW) } returns false

        val result = controller.findBySystem(authentication, query)

        assertEquals(listOf(allowed), result)
    }

    @Test
    fun `findCount delegates to service`() = runTest {
        val query = FindQueryInput()
        coEvery { service.findCount(query) } returns 42L

        assertEquals(42L, controller.findCount(query))
    }

    @Test
    fun `root fetches NIL collection`() = runTest {
        val root = createCollection(id = UUID.NIL)

        coEvery { service.getById(UUID.NIL) } returns root
        coEvery { permissions.isAllowed(authentication, root, PermissionAction.VIEW) } returns true

        val result = controller.root(authentication)

        assertSame(root, result)
        coVerify { service.getById(UUID.NIL) }
    }

    @Test
    fun `collection returns null when service returns null`() = runTest {
        val id = UUID.random()
        coEvery { service.getById(id) } returns null

        assertNull(controller.collection(authentication, id))
    }

    @Test
    fun `collection returns null when not allowed`() = runTest {
        val id = UUID.random()
        val collection = createCollection(id = id)

        coEvery { service.getById(id) } returns collection
        coEvery { permissions.isAllowed(authentication, collection, PermissionAction.VIEW) } returns false

        assertNull(controller.collection(authentication, id))
    }

    @Test
    fun `collection returns collection when allowed`() = runTest {
        val id = UUID.random()
        val collection = createCollection(id = id)

        coEvery { service.getById(id) } returns collection
        coEvery { permissions.isAllowed(authentication, collection, PermissionAction.VIEW) } returns true

        assertSame(collection, controller.collection(authentication, id))
    }

}
