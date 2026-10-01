package bosca.community.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class CommunityGroupsControllerTest {

    @Test
    fun `CommunityGroups object is a singleton`() {
        assertNotNull(CommunityGroups)
        assertSame(CommunityGroups, CommunityGroups)
    }

    @Test
    fun `CommunityGroups toString returns class name`() {
        val str = CommunityGroups.toString()
        assertNotNull(str)
    }
}
