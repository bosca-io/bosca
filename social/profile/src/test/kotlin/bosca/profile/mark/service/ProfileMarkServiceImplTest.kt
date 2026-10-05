package bosca.profile.mark.service

import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.model.ProfileMetadataIdsQuery
import bosca.profile.mark.repository.ProfileMarkRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileMarkServiceImplTest {

    private val repository = mockk<ProfileMarkRepository>()
    private val service = ProfileMarkServiceImpl(repository)

    @Test
    fun `getMarks by profile delegates to repository`() = runTest {
        val profileId = UUID.random()
        val marks = listOf(
            ProfileMark(id = 1, profileId = profileId),
            ProfileMark(id = 2, profileId = profileId)
        )

        coEvery { repository.findByProfileId(profileId, 10, 5) } returns marks

        val result = service.getMarks(profileId, 10, 5)

        assertEquals(2, result.size)
        assertEquals(marks, result)
    }

    @Test
    fun `getMarks by metadata delegates to repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val marks = listOf(ProfileMark(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1))

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1, 10, 0L) } returns marks

        val result = service.getMarks(profileId, metadataId, 1, 10, 0L)

        assertEquals(1, result.size)
    }

    @Test
    fun `getMarks by metadata ids delegates to repository`() = runTest {
        val profileId = UUID.random()
        val meta1 = UUID.random()
        val meta2 = UUID.random()
        val mark1 = ProfileMark(id = 1, profileId = profileId, metadataId = meta1, metadataVersion = 1)
        val mark2 = ProfileMark(id = 2, profileId = profileId, metadataId = meta2, metadataVersion = 1)

        coEvery { repository.findByProfileAndMetadataIds(ProfileMetadataIdsQuery(profileId, listOf(meta1, meta2))) } returns listOf(mark1, mark2)

        val result = service.getMarks(profileId, listOf(meta1, meta2))

        assertEquals(2, result.size)
        assertEquals(listOf(mark1, mark2), result)
    }

    @Test
    fun `getMarks by metadata ids returns empty for empty list`() = runTest {
        val profileId = UUID.random()

        val result = service.getMarks(profileId, emptyList())

        assertEquals(emptyList(), result)
    }

    @Test
    fun `getMarks by collection delegates to repository`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val marks = listOf(ProfileMark(id = 1, profileId = profileId, collectionId = collectionId))

        coEvery { repository.findByProfileAndCollection(profileId, collectionId, 10, 0L) } returns marks

        val result = service.getMarks(profileId, collectionId, 10, 0L)

        assertEquals(1, result.size)
    }

    @Test
    fun `getMarkCount by metadata delegates to repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()

        coEvery { repository.countByProfileAndMetadata(profileId, metadataId, 1) } returns 3L

        val result = service.getMarkCount(profileId, metadataId, 1)

        assertEquals(3L, result)
    }

    @Test
    fun `getMarkCount by collection delegates to repository`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()

        coEvery { repository.countByProfileAndCollection(profileId, collectionId) } returns 7L

        val result = service.getMarkCount(profileId, collectionId)

        assertEquals(7L, result)
    }

    @Test
    fun `getMarkCount by profile delegates to repository`() = runTest {
        val profileId = UUID.random()

        coEvery { repository.countByProfileId(profileId) } returns 15L

        val result = service.getMarkCount(profileId)

        assertEquals(15L, result)
    }

    @Test
    fun `addMark creates mark with all fields and calls repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val attrs = buildJsonObject { put("highlight", "text") }

        coEvery { repository.add(any()) } returns ProfileMark(
            id = 1,
            profileId = profileId,
            metadataId = metadataId,
            metadataVersion = 2,
            attributes = attrs
        )

        service.addMark(profileId, metadataId, 2, null, attrs)

        coVerify {
            repository.add(match {
                it.profileId == profileId &&
                    it.metadataId == metadataId &&
                    it.metadataVersion == 2 &&
                    it.attributes == attrs
            })
        }
    }

    @Test
    fun `addMark with collection creates mark correctly`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()

        coEvery { repository.add(any()) } returns ProfileMark(
            id = 1,
            profileId = profileId,
            collectionId = collectionId
        )

        service.addMark(profileId, null, null, collectionId, null)

        coVerify {
            repository.add(match {
                it.profileId == profileId &&
                    it.collectionId == collectionId &&
                    it.metadataId == null
            })
        }
    }

    @Test
    fun `deleteMark delegates to repository`() = runTest {
        val profileId = UUID.random()
        coEvery { repository.deleteById(42L, profileId) } returns ProfileMark(id = 42, profileId = profileId)

        service.deleteMark(42L, profileId)

        coVerify { repository.deleteById(42L, profileId) }
    }
}
