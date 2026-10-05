package bosca.db.mapper

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.postgresql.util.PGobject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
private data class MapperPayload(val name: String, val count: Int)

private enum class MapperState { READY, DONE }

class JsonDatabaseMappersTest {

    private val json = Json

    @Test
    fun `json mapper maps both result access forms and binds jsonb`() {
        val mapper = JsonMapper(json)
        val result = mockk<ResultSet>()
        every { result.getString(1) } returns "{\"count\":1}"
        every { result.getString("data") } returns null

        assertEquals("{\"count\":1}", mapper.map(kotlinx.serialization.json.JsonElement::class, emptyList(), result, 1).toString())
        assertNull(mapper.map(kotlinx.serialization.json.JsonElement::class, emptyList(), result, "data"))

        val statement = mockk<PreparedStatement>(relaxed = true)
        val captured = slot<PGobject>()
        val value = buildJsonObject { put("ok", true) }
        assertEquals(statement, mapper.bind(kotlinx.serialization.json.JsonElement::class, emptyList(), statement, 2, value))
        verify { statement.setObject(2, capture(captured)) }
        assertEquals("jsonb", captured.captured.type)
        assertEquals(value.toString(), captured.captured.value)

        mapper.bind(kotlinx.serialization.json.JsonElement::class, emptyList(), statement, 3, null)
        verify { statement.setNull(3, Types.OTHER) }
    }

    @Test
    fun `jsonb mapper maps both result access forms and binds nullable values`() {
        val result = mockk<ResultSet>()
        every { result.getString(1) } returns "{\"name\":\"one\",\"count\":1}"
        every { result.getString("payload") } returns "{\"name\":\"two\",\"count\":2}"
        assertEquals(MapperPayload("one", 1), JsonbMapper.map(json, MapperPayload.serializer(), result, 1))
        assertEquals(MapperPayload("two", 2), JsonbMapper.map(json, MapperPayload.serializer(), result, "payload"))

        every { result.getString(2) } returns null
        assertNull(JsonbMapper.map(json, MapperPayload.serializer(), result, 2))

        val statement = mockk<PreparedStatement>(relaxed = true)
        val captured = slot<PGobject>()
        assertEquals(statement, JsonbMapper.bind(json, MapperPayload.serializer(), statement, 1, MapperPayload("x", 3)))
        verify { statement.setObject(1, capture(captured)) }
        assertEquals("jsonb", captured.captured.type)
        assertEquals("{\"name\":\"x\",\"count\":3}", captured.captured.value)
        JsonbMapper.bind(json, MapperPayload.serializer(), statement, 2, null)
        verify { statement.setNull(2, Types.OTHER) }
    }

    @Test
    fun `serializable mapper maps both result access forms and binds nullable values`() {
        val mapper = SerializableMapper(json)
        val result = mockk<ResultSet>()
        every { result.getString(1) } returns "{\"name\":\"one\",\"count\":1}"
        every { result.getString("payload") } returns null
        assertEquals(MapperPayload("one", 1), mapper.map(MapperPayload.serializer(), result, 1))
        assertNull(mapper.map(MapperPayload.serializer(), result, "payload"))

        val statement = mockk<PreparedStatement>(relaxed = true)
        val captured = slot<PGobject>()
        assertEquals(statement, mapper.bind(MapperPayload.serializer(), statement, 4, MapperPayload("x", 3)))
        verify { statement.setObject(4, capture(captured)) }
        assertEquals("jsonb", captured.captured.type)
        assertEquals("{\"name\":\"x\",\"count\":3}", captured.captured.value)
        mapper.bind(MapperPayload.serializer(), statement, 5, null)
        verify { statement.setNull(5, Types.OTHER) }
    }

    @Test
    fun `enum mapper normalizes database casing and binds nullable values`() {
        val mapper = EnumMapper(MapperState::valueOf)
        val result = mockk<ResultSet>()
        every { result.getString(1) } returns "ready"
        every { result.getString("state") } returns "DONE"
        every { result.getString(2) } returns null
        assertEquals(MapperState.READY, mapper.map(MapperState::class, emptyList(), result, 1))
        assertEquals(MapperState.DONE, mapper.map(MapperState::class, emptyList(), result, "state"))
        assertNull(mapper.map(MapperState::class, emptyList(), result, 2))

        val statement = mockk<PreparedStatement>(relaxed = true)
        assertEquals(statement, mapper.bind(MapperState::class, emptyList(), statement, 1, MapperState.DONE))
        verify { statement.setString(1, "done") }
        mapper.bind(MapperState::class, emptyList(), statement, 2, null)
        verify { statement.setNull(2, Types.VARCHAR) }
    }
}
