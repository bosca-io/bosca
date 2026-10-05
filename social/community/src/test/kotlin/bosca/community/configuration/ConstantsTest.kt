package bosca.community.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstantsTest {

    @Test
    fun `COMPANION_NAME has expected value`() {
        assertEquals("Buddy", Constants.COMPANION_NAME)
    }

    @Test
    fun `COMPANION_NAME is not blank`() {
        assertTrue(Constants.COMPANION_NAME.isNotBlank())
    }
}
