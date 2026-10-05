package bosca.storage.service

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbstractObjectStorageServiceTest {

    @Test
    fun `AbstractObjectStorageService exists as a class`() {
        assertNotNull(AbstractObjectStorageService::class)
    }

    @Test
    fun `StringObjectPath implements ObjectPath`() {
        val path = StringObjectPath("test/path")
        assertTrue(path is ObjectPath)
    }

    @Test
    fun `StringObjectPath toString returns path`() {
        val path = StringObjectPath("my/object/path")
        assertTrue(path.toString() == "my/object/path")
    }
}
