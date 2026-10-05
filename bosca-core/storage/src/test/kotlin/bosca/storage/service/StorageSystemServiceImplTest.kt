package bosca.storage.service

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StorageSystemServiceImplTest {

    @Test
    fun `StorageSystemServiceImpl class exists`() {
        assertNotNull(StorageSystemServiceImpl::class)
    }

    @Test
    fun `StorageSystemServiceImpl has ServiceImplementation annotation`() {
        val annotation = StorageSystemServiceImpl::class.java.getAnnotation(
            bosca.service.annotation.ServiceImplementation::class.java
        )
        assertTrue(annotation != null, "Expected @ServiceImplementation annotation")
    }
}
