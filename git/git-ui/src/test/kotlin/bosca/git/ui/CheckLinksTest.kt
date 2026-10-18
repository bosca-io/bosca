package bosca.git.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckLinksTest {
    @Test
    fun `check links accept same origin and web destinations and reject executable or ambiguous URLs`() {
        for (url in listOf("/pipelines/run", "https://builds.example/run/1", "http://localhost:8093/pipelines/run")) {
            assertEquals(url, safeCheckHref(url))
        }
        for (url in listOf(null, "", "javascript:alert(1)", "//example.com/run", "/\\example.com", "/\nexample.com", "data:text/html,test")) {
            assertEquals("", safeCheckHref(url))
        }
    }
}
