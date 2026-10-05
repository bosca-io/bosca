package bosca.profile.bookmark.service

import bosca.profile.bookmark.model.ProfileBookmark
import bosca.profile.bookmark.repository.ProfileBookmarkRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileBookmarkServiceImplTest {

    private val repository = mockk<ProfileBookmarkRepository>()
    private val service = ProfileBookmarkServiceImpl(repository)

    @Test
    fun `getBookmarks delegates to repository with correct parameters`() = runTest {
        val profileId = UUID.random()
        val bookmarks = listOf(
            ProfileBookmark(id = 1, profileId = profileId, metadataId = UUID.random(), metadataVersion = 1),
            ProfileBookmark(id = 2, profileId = profileId, collectionId = UUID.random())
        )

        coEvery { repository.findByProfileId(profileId, 10, 0) } returns bookmarks

        val result = service.getBookmarks(profileId, 10, 0)

        assertEquals(2, result.size)
        assertEquals(bookmarks, result)
    }

    @Test
    fun `getBookmarkCount delegates to repository`() = runTest {
        val profileId = UUID.random()

        coEvery { repository.countByProfileId(profileId) } returns 5L

        val result = service.getBookmarkCount(profileId)

        assertEquals(5L, result)
    }

    @Test
    fun `getBookmark by metadata delegates to repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val bookmark = ProfileBookmark(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1)

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns bookmark

        val result = service.getBookmark(profileId, metadataId, 1)

        assertEquals(bookmark, result)
    }

    @Test
    fun `getBookmark by metadata returns null when not found`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns null

        val result = service.getBookmark(profileId, metadataId, 1)

        assertNull(result)
    }

    @Test
    fun `getBookmark by collection delegates to repository`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val bookmark = ProfileBookmark(id = 1, profileId = profileId, collectionId = collectionId)

        coEvery { repository.findByProfileAndCollection(profileId, collectionId) } returns bookmark

        val result = service.getBookmark(profileId, collectionId)

        assertEquals(bookmark, result)
    }

    @Test
    fun `addBookmark creates bookmark with all fields and calls repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val attributes = buildJsonObject { put("key", "value") }

        coEvery { repository.add(any()) } returns ProfileBookmark(
            id = 1,
            profileId = profileId,
            metadataId = metadataId,
            metadataVersion = 2,
            attributes = attributes
        )

        service.addBookmark(profileId, metadataId, 2, null, attributes)

        coVerify {
            repository.add(match {
                it.profileId == profileId &&
                    it.metadataId == metadataId &&
                    it.metadataVersion == 2 &&
                    it.attributes == attributes
            })
        }
    }

    @Test
    fun `addBookmark with collection creates bookmark correctly`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()

        coEvery { repository.add(any()) } returns ProfileBookmark(
            id = 1,
            profileId = profileId,
            collectionId = collectionId
        )

        service.addBookmark(profileId, null, null, collectionId, null)

        coVerify {
            repository.add(match {
                it.profileId == profileId &&
                    it.collectionId == collectionId &&
                    it.metadataId == null
            })
        }
    }

    @Test
    fun `deleteBookmark by metadata finds and deletes bookmark`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val bookmark = ProfileBookmark(id = 42, profileId = profileId, metadataId = metadataId, metadataVersion = 1)

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns bookmark
        coEvery { repository.deleteById(42) } returns Unit

        service.deleteBookmark(profileId, metadataId, 1, null)

        coVerify { repository.deleteById(42) }
    }

    @Test
    fun `deleteBookmark by metadata does nothing when bookmark not found`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns null

        service.deleteBookmark(profileId, metadataId, 1, null)

        coVerify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun `deleteBookmark by collection finds and deletes bookmark`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val bookmark = ProfileBookmark(id = 99, profileId = profileId, collectionId = collectionId)

        coEvery { repository.findByProfileAndCollection(profileId, collectionId) } returns bookmark
        coEvery { repository.deleteById(99) } returns Unit

        service.deleteBookmark(profileId, null, null, collectionId)

        coVerify { repository.deleteById(99) }
    }

    @Test
    fun `deleteBookmark by collection does nothing when bookmark not found`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()

        coEvery { repository.findByProfileAndCollection(profileId, collectionId) } returns null

        service.deleteBookmark(profileId, null, null, collectionId)

        coVerify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun `deleteBookmark does nothing when neither metadata nor collection provided`() = runTest {
        val profileId = UUID.random()

        service.deleteBookmark(profileId, null, null, null)

        coVerify(exactly = 0) { repository.findByProfileAndMetadata(any(), any(), any()) }
        coVerify(exactly = 0) { repository.findByProfileAndCollection(any(), any()) }
        coVerify(exactly = 0) { repository.deleteById(any()) }
    }
}
