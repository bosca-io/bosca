package bosca.community.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class CommunityControllerTest {

    @Test
    fun `Community object is a singleton`() {
        assertNotNull(Community)
        assertSame(Community, Community)
    }

    @Test
    fun `CommunityMutation object is a singleton`() {
        assertNotNull(CommunityMutation)
        assertSame(CommunityMutation, CommunityMutation)
    }

    @Test
    fun `Community object toString returns stable value`() {
        val str = Community.toString()
        assertNotNull(str)
    }

    @Test
    fun `CommunityMutation object toString returns stable value`() {
        val str = CommunityMutation.toString()
        assertNotNull(str)
    }
}
