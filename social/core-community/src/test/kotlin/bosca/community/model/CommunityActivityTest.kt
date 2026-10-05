package bosca.community.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CommunityActivityTest {

    private val activityId = Uuid.random()
    private val groupId = Uuid.random()

    @Test
    fun `CommunityActivity stores all properties`() {
        val content = JsonPrimitive("some content")
        val schedule = JsonPrimitive("weekly")
        val activity = CommunityActivity(
            id = activityId,
            groupId = groupId,
            name = "Bible Study",
            description = "Weekly study group",
            type = "study",
            content = content,
            schedule = schedule
        )
        assertEquals(activityId, activity.id)
        assertEquals(groupId, activity.groupId)
        assertEquals("Bible Study", activity.name)
        assertEquals("Weekly study group", activity.description)
        assertEquals("study", activity.type)
        assertEquals(content, activity.content)
        assertEquals(schedule, activity.schedule)
    }

    @Test
    fun `CommunityActivity allows null content and schedule`() {
        val activity = CommunityActivity(
            id = activityId,
            groupId = groupId,
            name = "Prayer",
            description = "Open prayer",
            type = "prayer",
            content = null,
            schedule = null
        )
        assertNull(activity.content)
        assertNull(activity.schedule)
    }

    @Test
    fun `CommunityActivity equality for same values`() {
        val a = CommunityActivity(activityId, groupId, "N", "D", "T", null, null)
        val b = CommunityActivity(activityId, groupId, "N", "D", "T", null, null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `CommunityActivity content can be JsonNull`() {
        val activity = CommunityActivity(activityId, groupId, "N", "D", "T", JsonNull, null)
        assertEquals(JsonNull, activity.content)
    }
}
