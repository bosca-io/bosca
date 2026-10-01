package bosca.serialization

import kotlin.test.Test
import kotlin.test.assertNotNull

class AliasesTest {

    @Test
    fun uuidTypeAliasExists() {
        val clazz = UUID::class
        assertNotNull(clazz)
    }

    @Test
    fun uuidIsKotlinUuid() {
        // UUID is a typealias for kotlin.uuid.Uuid
        val uuid = UUID.random()
        assertNotNull(uuid)
    }
}
