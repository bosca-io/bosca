package bosca.pages

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class PageContextTest {

    @Test
    fun isObject() {
        assertNotNull(PageContext)
    }

    @Test
    fun setPageDetailsThrowsOutsideContext() {
        assertFailsWith<IllegalStateException> {
            PageContext.setPageDetails(PageDetails("test", "test"))
        }
    }

    @Test
    fun runExecutesBlock() {
        val result = PageContext.run { "hello" }
        kotlin.test.assertEquals("hello", result)
    }
}
