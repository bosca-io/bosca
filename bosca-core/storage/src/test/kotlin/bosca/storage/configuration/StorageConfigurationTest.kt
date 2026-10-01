package bosca.storage.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StorageConfigurationTest {

    @Test
    fun fieldsArePreserved() {
        val config = StorageConfiguration(
            type = "s3",
            urlPrefix = "https://cdn.example.com",
            bucket = "my-bucket"
        )
        assertEquals("s3", config.type)
        assertEquals("https://cdn.example.com", config.urlPrefix)
        assertEquals("my-bucket", config.bucket)
    }

    @Test
    fun defaultValues() {
        val config = StorageConfiguration(type = "noop", urlPrefix = "http://localhost")
        assertNull(config.urlUploadPrefix)
        assertNull(config.bucket)
        assertFalse(config.createBucket)
        assertNull(config.basePath)
        assertNull(config.forcePathStyle)
        assertNull(config.region)
        assertNull(config.endpoint)
        assertNull(config.accessKeyId)
        assertNull(config.accessKeySecret)
    }

    @Test
    fun allFieldsSet() {
        val config = StorageConfiguration(
            type = "s3",
            urlPrefix = "https://cdn.example.com",
            urlUploadPrefix = "https://upload.example.com",
            bucket = "my-bucket",
            createBucket = true,
            basePath = "/data",
            forcePathStyle = true,
            region = "us-east-1",
            endpoint = "https://s3.amazonaws.com",
            accessKeyId = "AKID",
            accessKeySecret = "secret"
        )
        assertEquals("https://upload.example.com", config.urlUploadPrefix)
        assertEquals(true, config.createBucket)
        assertEquals("/data", config.basePath)
        assertEquals(true, config.forcePathStyle)
        assertEquals("us-east-1", config.region)
    }

    @Test
    fun dataClassEquality() {
        val a = StorageConfiguration(type = "s3", urlPrefix = "http://a")
        val b = StorageConfiguration(type = "s3", urlPrefix = "http://a")
        assertEquals(a, b)
    }
}
