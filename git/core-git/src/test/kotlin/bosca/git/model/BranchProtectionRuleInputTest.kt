package bosca.git.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BranchProtectionRuleInputTest {

    @Test
    fun `GraphQL-shaped input decodes without persistence fields`() {
        val input = Json.decodeFromString<BranchProtectionRuleInput>(
            """{"pattern":"release/*","requirePullRequest":true}"""
        )

        assertEquals("release/*", input.pattern)
        assertEquals(1, input.requiredApprovals)
        assertEquals(emptyList(), input.requireStatusChecks)
        assertFalse(input.allowForcePush)
    }
}
