package bosca.db.mapper

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull

class DefaultMappersTest {

    private val mappers = DefaultMappers(Json)

    @Test
    fun `int mapper is initialized`() {
        assertNotNull(mappers.int)
    }

    @Test
    fun `long mapper is initialized`() {
        assertNotNull(mappers.long)
    }

    @Test
    fun `float mapper is initialized`() {
        assertNotNull(mappers.float)
    }

    @Test
    fun `double mapper is initialized`() {
        assertNotNull(mappers.double)
    }

    @Test
    fun `string mapper is initialized`() {
        assertNotNull(mappers.string)
    }

    @Test
    fun `boolean mapper is initialized`() {
        assertNotNull(mappers.boolean)
    }

    @Test
    fun `uuid mapper is initialized`() {
        assertNotNull(mappers.uuid)
    }

    @Test
    fun `array mapper is initialized`() {
        assertNotNull(mappers.array)
    }

    @Test
    fun `byteArray mapper is initialized`() {
        assertNotNull(mappers.byteArray)
    }

    @Test
    fun `offsetDateTime mapper is initialized`() {
        assertNotNull(mappers.offsetDateTime)
    }

    @Test
    fun `localDateTime mapper is initialized`() {
        assertNotNull(mappers.localDateTime)
    }

    @Test
    fun `instant mapper is initialized`() {
        assertNotNull(mappers.instant)
    }

    @Test
    fun `jsonElement mapper is initialized`() {
        assertNotNull(mappers.jsonElement)
    }

    @Test
    fun `serializable mapper is initialized`() {
        assertNotNull(mappers.serializable)
    }

    @Test
    fun `enum mapper creates mapper for given enum class`() {
        val mapper = mappers.enum(TestEnum::class)
        assertNotNull(mapper)
    }

    private enum class TestEnum {
        VALUE_A,
        VALUE_B
    }
}
