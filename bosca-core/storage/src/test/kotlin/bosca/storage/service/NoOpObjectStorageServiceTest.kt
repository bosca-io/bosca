package bosca.storage.service

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NoOpObjectStorageServiceTest {

    private val service = NoOpObjectStorageService()

    @Test
    fun `implements ObjectStorageService interface`() {
        assertIs<ObjectStorageService>(service)
    }

    @Test
    fun `all methods throw NotImplementedError`() {
        // The NoOpObjectStorageService has all methods throwing TODO("Not yet implemented"),
        // which translates to NotImplementedError at runtime
        assertFailsWith<NotImplementedError> {
            kotlinx.coroutines.test.runTest {
                service.getString(object : ObjectPath {
                    override fun toString() = "test/path"
                })
            }
        }
    }

    @Test
    fun `delete throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            kotlinx.coroutines.test.runTest {
                service.delete(object : ObjectPath {
                    override fun toString() = "test/path"
                })
            }
        }
    }

    @Test
    fun `getInputStream throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            kotlinx.coroutines.test.runTest {
                service.getInputStream(object : ObjectPath {
                    override fun toString() = "test/path"
                })
            }
        }
    }

    @Test
    fun `getInputStreamRange throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            kotlinx.coroutines.test.runTest {
                service.getInputStreamRange(
                    object : ObjectPath {
                        override fun toString() = "test/path"
                    },
                    0L..100L
                )
            }
        }
    }

    @Test
    fun `setInputStream throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            kotlinx.coroutines.test.runTest {
                service.setInputStream(
                    object : ObjectPath {
                        override fun toString() = "test/path"
                    },
                    "test".byteInputStream(),
                    4L
                )
            }
        }
    }
}
