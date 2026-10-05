package bosca.community.model

import kotlin.test.Test
import kotlin.test.assertTrue

class CommunityGroupTypeTest {
    @Test
    fun hasExpectedValues() {
        val values = CommunityGroupType.entries.map { it.name }
        assertTrue(values.isNotEmpty())
    }
}
