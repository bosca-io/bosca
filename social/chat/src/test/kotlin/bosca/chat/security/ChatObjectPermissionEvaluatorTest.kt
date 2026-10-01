package bosca.chat.security

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatObjectPermissionEvaluatorTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val evaluator = ChatObjectPermissionEvaluator(
        metadataService,
        metadataPermissionEvaluator,
        collectionService,
        collectionPermissionEvaluator,
    )
    private val authentication = mockk<AuthenticationContext>()

    @Test
    fun `metadata channel delegates to metadata VIEW authorization`() = runTest {
        val objectId = UUID.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(objectId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        } just Runs

        evaluator.verifyAllowed(authentication, ChatObjectType.METADATA, objectId)

        coVerify(exactly = 1) {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }

    @Test
    fun `collection channel delegates to collection VIEW authorization`() = runTest {
        val objectId = UUID.random()
        val collection = mockk<Collection>()
        coEvery { collectionService.getById(objectId) } returns collection
        coEvery {
            collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.VIEW)
        } just Runs

        evaluator.verifyAllowed(authentication, ChatObjectType.COLLECTION, objectId)

        coVerify(exactly = 1) {
            collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.VIEW)
        }
    }

    @Test
    fun `missing or unsupported objects fail closed`() = runTest {
        val objectId = UUID.random()
        coEvery { metadataService.getById(objectId) } returns null

        assertFailsWith<NoSuchElementException> {
            evaluator.verifyAllowed(authentication, ChatObjectType.METADATA, objectId)
        }
        assertFailsWith<IllegalArgumentException> {
            evaluator.verifyAllowed(authentication, ChatObjectType.LOCALIZATION_KEY, objectId)
        }
    }

    @Test
    fun `channel checks reject incomplete references and unsupported object types`() = runTest {
        val objectId = UUID.random()

        assertFailsWith<IllegalStateException> {
            evaluator.verifyAllowed(
                authentication,
                ChatChannel(objectId, name = "Broken", type = ChatChannelType.GROUP, objectId = objectId),
            )
        }
        assertFalse(
            evaluator.isAllowed(
                authentication,
                ChatChannel(
                    UUID.random(),
                    name = "Future",
                    type = ChatChannelType.GROUP,
                    objectType = ChatObjectType.CALENDAR_EVENT,
                    objectId = objectId,
                ),
            ),
        )
        assertTrue(
            evaluator.isAllowed(
                authentication,
                ChatChannel(UUID.random(), name = "General", type = ChatChannelType.GROUP),
            ),
        )
    }

    @Test
    fun `batch object authorization loads metadata permissions once`() = runTest {
        val objectId = UUID.random()
        val channel = ChatChannel(
            UUID.random(),
            name = "Metadata",
            type = ChatChannelType.GROUP,
            objectType = ChatObjectType.METADATA,
            objectId = objectId,
        )
        val metadata = mockk<Metadata>()
        val authentication2 = mockk<AuthenticationContext>()
        val authentications = listOf(authentication, authentication2)
        coEvery { metadataService.getById(objectId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentications, metadata, PermissionAction.VIEW)
        } returns listOf(true, false)

        assertEquals(listOf(true, false), evaluator.isAllowed(authentications, channel))

        coVerify(exactly = 1) { metadataService.getById(objectId) }
        coVerify(exactly = 1) {
            metadataPermissionEvaluator.isAllowed(authentications, metadata, PermissionAction.VIEW)
        }
        coVerify(exactly = 0) {
            metadataPermissionEvaluator.isAllowed(any<AuthenticationContext>(), metadata, any())
        }
    }
}
