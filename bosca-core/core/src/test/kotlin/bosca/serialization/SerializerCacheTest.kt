package bosca.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Registered under a custom serial name, so only the name index (never Class.forName) can find it. */
@Serializable
@SerialName("test.cache.Renamed")
data class CacheTestRenamed(val id: String)

/** Never registered; its serial name is the FQCN, so only the Class.forName fallback can find it. */
@Serializable
data class CacheTestFallback(val id: String)

private interface CacheTestContract

@Serializable
@SerialName("test.cache.ContractImplementation")
private data class CacheTestContractImplementation(val id: String) : CacheTestContract

class SerializerCacheTest {

    @Test
    fun `a registered serializer resolves by class`() {
        SerializerCache.register(CacheTestRenamed::class.java, CacheTestRenamed.serializer())
        assertSame(CacheTestRenamed.serializer(), SerializerCache.get(CacheTestRenamed::class.java))
    }

    @Test
    fun `a registered serializer resolves by its serial name`() {
        SerializerCache.register(CacheTestRenamed::class.java, CacheTestRenamed.serializer())
        assertSame(CacheTestRenamed.serializer(), SerializerCache.get("test.cache.Renamed"))
    }

    @Test
    fun `an unregistered type resolves by its fully-qualified serial name via the class fallback`() {
        val name = CacheTestFallback.serializer().descriptor.serialName
        val resolved = SerializerCache.get(name)
        assertEquals(CacheTestFallback.serializer().descriptor.serialName, resolved?.descriptor?.serialName)
        // The fallback result is cached — the second lookup hits the name index.
        assertSame(resolved, SerializerCache.get(name))
    }

    @Test
    fun `an unknown serial name resolves to null`() {
        assertNull(SerializerCache.get("com.example.NoSuchType"))
    }

    @Test
    fun `an unregistered class resolves to null by class lookup`() {
        assertNull(SerializerCache.get(java.awt.Point::class.java))
    }

    @Test
    fun `catalog exposes registered serial names and their explicit domain supertypes`() {
        SerializerCache.register(
            CacheTestContractImplementation::class.java,
            CacheTestContractImplementation.serializer(),
            CacheTestContract::class.java,
        )

        assertTrue("test.cache.ContractImplementation" in SerializerCache.cataloguedTypes())
        assertTrue(CacheTestContract::class.java.name in SerializerCache.cataloguedTypes())
        assertSame(CacheTestContractImplementation::class.java, SerializerCache.classFor("test.cache.ContractImplementation"))
        assertSame(CacheTestContract::class.java, SerializerCache.classFor(CacheTestContract::class.java.name))
    }
}
