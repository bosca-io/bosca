package bosca.profile.guide.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileGuideModelTest {

    // -- ProfileGuideProgress --

    @Test
    fun `ProfileGuideProgress creation with required fields`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = 1,
            attributes = null
        )
        assertEquals(profileId, progress.profileId)
        assertEquals(metadataId, progress.metadataId)
        assertEquals(1, progress.version)
        assertNull(progress.attributes)
    }

    @Test
    fun `ProfileGuideProgress completedStepIds defaults to empty list`() {
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = null
        )
        assertTrue(progress.completedStepIds.isEmpty())
    }

    @Test
    fun `ProfileGuideProgress started and modified have default values`() {
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = null
        )
        assertNotNull(progress.started)
        assertNotNull(progress.modified)
    }

    @Test
    fun `ProfileGuideProgress creation with all fields`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonPrimitive("step-data")
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = 3,
            attributes = attrs,
            completedStepIds = listOf(1L, 2L, 3L)
        )
        assertEquals(profileId, progress.profileId)
        assertEquals(metadataId, progress.metadataId)
        assertEquals(3, progress.version)
        assertEquals(attrs, progress.attributes)
        assertEquals(listOf(1L, 2L, 3L), progress.completedStepIds)
    }

    @Test
    fun `ProfileGuideProgress data class equality`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val now = OffsetDateTime.now()
        val a = ProfileGuideProgress(profileId = profileId, metadataId = metadataId, version = 1, attributes = null, started = now, modified = now)
        val b = ProfileGuideProgress(profileId = profileId, metadataId = metadataId, version = 1, attributes = null, started = now, modified = now)
        assertEquals(a, b)
    }

    // -- ProfileGuideHistory --

    @Test
    fun `ProfileGuideHistory creation with required fields`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonPrimitive("history-data")
        val history = ProfileGuideHistory(
            profileId = profileId,
            metadataId = metadataId,
            version = 2,
            attributes = attrs
        )
        assertEquals(profileId, history.profileId)
        assertEquals(metadataId, history.metadataId)
        assertEquals(2, history.version)
        assertEquals(attrs, history.attributes)
    }

    @Test
    fun `ProfileGuideHistory id defaults to zero`() {
        val history = ProfileGuideHistory(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = JsonNull
        )
        assertEquals(0L, history.id)
    }

    @Test
    fun `ProfileGuideHistory completed defaults to null`() {
        val history = ProfileGuideHistory(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = JsonNull
        )
        assertNull(history.completed)
    }

    @Test
    fun `ProfileGuideHistory creation with all fields`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonPrimitive("full")
        val history = ProfileGuideHistory(
            id = 99L,
            profileId = profileId,
            metadataId = metadataId,
            version = 5,
            attributes = attrs
        )
        assertEquals(99L, history.id)
        assertEquals(profileId, history.profileId)
        assertEquals(metadataId, history.metadataId)
        assertEquals(5, history.version)
        assertEquals(attrs, history.attributes)
    }

    @Test
    fun `ProfileGuideHistory data class equality`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonPrimitive("x")
        val a = ProfileGuideHistory(id = 1, profileId = profileId, metadataId = metadataId, version = 1, attributes = attrs)
        val b = ProfileGuideHistory(id = 1, profileId = profileId, metadataId = metadataId, version = 1, attributes = attrs)
        assertEquals(a, b)
    }

    @Test
    fun `GuideProgressStatistics preserves aggregate counts`() {
        val statistics = GuideProgressStatistics(
            activeProgressions = 4,
            activeProfiles = 3,
            historicalProgressions = 12,
            completions = 10,
            totalProgressions = 16,
            uniqueProfiles = 14,
        )

        assertEquals(4, statistics.activeProgressions)
        assertEquals(3, statistics.activeProfiles)
        assertEquals(12, statistics.historicalProgressions)
        assertEquals(10, statistics.completions)
        assertEquals(16, statistics.totalProgressions)
        assertEquals(14, statistics.uniqueProfiles)
    }
}
