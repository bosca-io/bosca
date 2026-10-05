package bosca.community.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CommunityMemberModelsTest {

    // --- CommunityGroupMember ---

    @Test
    fun `CommunityGroupMember stores groupId and profileId`() {
        val groupId = Uuid.random()
        val profileId = Uuid.random()
        val member = CommunityGroupMember(groupId = groupId, profileId = profileId)
        assertEquals(groupId, member.groupId)
        assertEquals(profileId, member.profileId)
    }

    // ChatChannelMember coverage lives in core-chat now that the model
    // moved out of community — see core-chat ChatChannelMemberTest.

    // --- CommunityGroupSignupToken ---

    @Test
    fun `CommunityGroupSignupToken stores token and groupId`() {
        val groupId = Uuid.random()
        val token = CommunityGroupSignupToken(
            token = "abc123",
            groupId = groupId
        )
        assertEquals("abc123", token.token)
        assertEquals(groupId, token.groupId)
    }

    // --- CommunityGroupSignupEmail ---

    @Test
    fun `CommunityGroupSignupEmail stores all properties`() {
        val groupId = Uuid.random()
        val now = java.time.OffsetDateTime.now()
        val email = CommunityGroupSignupEmail(
            email = "test@example.com",
            groupId = groupId,
            created = now,
            expires = now.plusDays(30)
        )
        assertEquals("test@example.com", email.email)
        assertEquals(groupId, email.groupId)
    }

    // --- CommunityActivity ---

    @Test
    fun `CommunityActivity stores all properties`() {
        val id = Uuid.random()
        val groupId = Uuid.random()
        val activity = CommunityActivity(
            id = id,
            groupId = groupId,
            name = "Bible Study",
            description = "Weekly study",
            type = "study",
            content = null,
            schedule = null
        )
        assertEquals(id, activity.id)
        assertEquals(groupId, activity.groupId)
        assertEquals("Bible Study", activity.name)
        assertEquals("Weekly study", activity.description)
        assertEquals("study", activity.type)
        assertNull(activity.content)
        assertNull(activity.schedule)
    }
}
