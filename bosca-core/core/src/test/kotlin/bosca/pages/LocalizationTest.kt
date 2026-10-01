package bosca.pages

import kotlin.test.Test
import kotlin.test.assertFailsWith

class LocalizationTest {

    @Test
    fun `PageContext throws when setting details outside of run`() {
        assertFailsWith<IllegalStateException> {
            PageContext.setPageDetails(PageDetails(title = "Test"))
        }
    }

    @Test
    fun `PageContext throws when setting localization outside of run`() {
        assertFailsWith<IllegalStateException> {
            PageContext.setLocalization(MapLocalization(emptyMap()))
        }
    }

    @Test
    fun `PageContext throws when accessing details without setting`() {
        assertFailsWith<IllegalStateException> {
            PageContext.details
        }
    }
}
