package bosca.cdn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class NoOpCdnManagerTest {

    @Test
    fun `NoOpCdnManager clearCache throws error`() = runTest {
        val manager = NoOpCdnManager()
        val error = assertFailsWith<IllegalStateException> {
            manager.clearCache()
        }
        assertEquals("CDN is not configured", error.message)
    }
}
