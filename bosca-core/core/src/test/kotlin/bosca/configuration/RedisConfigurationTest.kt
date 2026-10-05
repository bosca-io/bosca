package bosca.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RedisConfigurationTest {

    @Test
    fun `RedisConnectionConfig stores host and port`() {
        val config = RedisConnectionConfig(host = "localhost", port = 6379)
        assertEquals("localhost", config.host)
        assertEquals(6379, config.port)
    }

    @Test
    fun `RedisConnectionConfig equality is based on all fields`() {
        val c1 = RedisConnectionConfig(host = "localhost", port = 6379)
        val c2 = RedisConnectionConfig(host = "localhost", port = 6379)
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `RedisConnectionConfig inequality when host differs`() {
        val c1 = RedisConnectionConfig(host = "host1", port = 6379)
        val c2 = RedisConnectionConfig(host = "host2", port = 6379)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `RedisConnectionConfig inequality when port differs`() {
        val c1 = RedisConnectionConfig(host = "localhost", port = 6379)
        val c2 = RedisConnectionConfig(host = "localhost", port = 6380)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `RedisConnectionConfig copy allows changing host`() {
        val config = RedisConnectionConfig(host = "localhost", port = 6379)
        val copied = config.copy(host = "redis.example.com")
        assertEquals("redis.example.com", copied.host)
        assertEquals(6379, copied.port)
    }

    @Test
    fun `RedisConnectionConfig copy allows changing port`() {
        val config = RedisConnectionConfig(host = "localhost", port = 6379)
        val copied = config.copy(port = 6380)
        assertEquals("localhost", copied.host)
        assertEquals(6380, copied.port)
    }

    @Test
    fun `RedisConnectionConfig with non-standard port`() {
        val config = RedisConnectionConfig(host = "10.0.0.1", port = 16379)
        assertEquals("10.0.0.1", config.host)
        assertEquals(16379, config.port)
    }
}
