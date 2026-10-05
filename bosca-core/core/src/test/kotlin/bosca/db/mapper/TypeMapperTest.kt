package bosca.db.mapper

import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class TypeMapperTest {

    @Test
    fun `TypeMapper for String can be instantiated`() {
        val mapper = TypeMapper(String::class)
        assertNotNull(mapper)
    }

    @Test
    fun `TypeMapper for Int can be instantiated`() {
        val mapper = TypeMapper(Int::class)
        assertNotNull(mapper)
    }

    @Test
    fun `TypeMapper for Boolean can be instantiated`() {
        val mapper = TypeMapper(Boolean::class)
        assertNotNull(mapper)
    }

    @Test
    fun `TypeMapper for Long can be instantiated`() {
        val mapper = TypeMapper(Long::class)
        assertNotNull(mapper)
    }

    @Test
    fun `TypeMapper for Double can be instantiated`() {
        val mapper = TypeMapper(Double::class)
        assertNotNull(mapper)
    }

    @Test
    fun `TypeMapper bind null String sets VARCHAR type`() {
        val mapper = TypeMapper(String::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(String::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.VARCHAR) }
    }

    @Test
    fun `TypeMapper bind null Int sets INTEGER type`() {
        val mapper = TypeMapper(Int::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Int::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.INTEGER) }
    }

    @Test
    fun `TypeMapper bind null Boolean sets BOOLEAN type`() {
        val mapper = TypeMapper(Boolean::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Boolean::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.BOOLEAN) }
    }

    @Test
    fun `TypeMapper bind null Long sets BIGINT type`() {
        val mapper = TypeMapper(Long::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Long::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.BIGINT) }
    }

    @Test
    fun `TypeMapper bind null Double sets DOUBLE type`() {
        val mapper = TypeMapper(Double::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Double::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.DOUBLE) }
    }

    @Test
    fun `TypeMapper bind null Float sets FLOAT type`() {
        val mapper = TypeMapper(Float::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Float::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.FLOAT) }
    }

    @Test
    fun `TypeMapper bind null Short sets SMALLINT type`() {
        val mapper = TypeMapper(Short::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Short::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.SMALLINT) }
    }

    @Test
    fun `TypeMapper bind null Byte sets TINYINT type`() {
        val mapper = TypeMapper(Byte::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Byte::class, emptyList(), stmt, 1, null)
        verify { stmt.setNull(1, Types.TINYINT) }
    }

    @Test
    fun `TypeMapper bind non-null String calls setObject`() {
        val mapper = TypeMapper(String::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(String::class, emptyList(), stmt, 1, "hello")
        verify { stmt.setObject(1, "hello") }
    }

    @Test
    fun `TypeMapper bind non-null Int calls setObject`() {
        val mapper = TypeMapper(Int::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        mapper.bind(Int::class, emptyList(), stmt, 1, 42)
        verify { stmt.setObject(1, 42) }
    }

    @Test
    fun `TypeMapper bind returns the prepared statement`() {
        val mapper = TypeMapper(String::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        val result = mapper.bind(String::class, emptyList(), stmt, 1, "test")
        assertNotNull(result)
    }

    // ── Array bind tests ──

    private enum class Color { RED, GREEN, BLUE }

    private fun mockStmtWithConnection(): Pair<PreparedStatement, Connection> {
        val sqlArray = mockk<java.sql.Array>(relaxed = true)
        val connection = mockk<Connection>(relaxed = true) {
            every { createArrayOf(any(), any()) } returns sqlArray
        }
        val stmt = mockk<PreparedStatement>(relaxed = true) {
            every { getConnection() } returns connection
        }
        return stmt to connection
    }

    @Test
    fun `bind String array creates varchar sql array`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()
        val value = arrayOf("a", "b")
        @Suppress("UNCHECKED_CAST")
        mapper.bind(Array::class, listOf(String::class), stmt, 1, value as Array<Any>)
        verify { connection.createArrayOf("varchar", value) }
    }

    @Test
    fun `bind Int array creates integer sql array`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()
        val value = arrayOf(1, 2, 3)
        @Suppress("UNCHECKED_CAST")
        mapper.bind(Array::class, listOf(Int::class), stmt, 1, value as Array<Any>)
        verify { connection.createArrayOf("integer", value) }
    }

    @Test
    fun `bind Long array creates bigint sql array`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()
        val value = arrayOf(1L, 2L)
        @Suppress("UNCHECKED_CAST")
        mapper.bind(Array::class, listOf(Long::class), stmt, 1, value as Array<Any>)
        verify { connection.createArrayOf("bigint", value) }
    }

    @Test
    fun `bind enum array lowercases names to varchar`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()
        val value = arrayOf(Color.RED, Color.GREEN)
        @Suppress("UNCHECKED_CAST")
        mapper.bind(Array::class, listOf(Color::class), stmt, 1, value as Array<Any>)
        verify { connection.createArrayOf("varchar", arrayOf("red", "green")) }
    }

    @Test
    fun `bind Array with empty arguments falls back to setObject`() {
        val mapper = TypeMapper(Array::class)
        val stmt = mockk<PreparedStatement>(relaxed = true)
        val value = arrayOf("x")
        @Suppress("UNCHECKED_CAST")
        mapper.bind(Array::class, emptyList(), stmt, 1, value as Array<Any>)
        verify { stmt.setObject(1, value) }
    }

    // ── Array map tests ──

    @Test
    fun `map reads enum array with case-insensitive matching`() {
        val mapper = TypeMapper(Array::class)
        val sqlArray = mockk<java.sql.Array>(relaxed = true) {
            every { array } returns arrayOf("red", "BLUE")
        }
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray(1) } returns sqlArray
        }
        @Suppress("UNCHECKED_CAST")
        val result = mapper.map(Array::class, listOf(Color::class), rs, 1) as Array<Any>?
        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals(Color.RED, result[0])
        assertEquals(Color.BLUE, result[1])
    }

    @Test
    fun `map returns null for null sql array`() {
        val mapper = TypeMapper(Array::class)
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray(1) } returns null
        }
        val result = mapper.map(Array::class, emptyList(), rs, 1)
        assertNull(result)
    }

    @Test
    fun `map by name reads enum array with case-insensitive matching`() {
        val mapper = TypeMapper(Array::class)
        val sqlArray = mockk<java.sql.Array>(relaxed = true) {
            every { array } returns arrayOf("green")
        }
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray("colors") } returns sqlArray
        }
        @Suppress("UNCHECKED_CAST")
        val result = mapper.map(Array::class, listOf(Color::class), rs, "colors") as Array<Any>?
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals(Color.GREEN, result[0])
    }

    @Test
    fun `map non-enum array returns raw array`() {
        val mapper = TypeMapper(Array::class)
        val rawArray = arrayOf("a", "b")
        val sqlArray = mockk<java.sql.Array>(relaxed = true) {
            every { array } returns rawArray
        }
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray(1) } returns sqlArray
        }
        @Suppress("UNCHECKED_CAST")
        val result = mapper.map(Array::class, listOf(String::class), rs, 1) as Array<Any>?
        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("a", result[0])
        assertEquals("b", result[1])
    }

    // ── UUID-array bind: kotlin.uuid.Uuid → java.util.UUID via createArrayOf("uuid", …) ──

    @Test
    @OptIn(ExperimentalUuidApi::class)
    fun `bind UUID array converts kotlin Uuid values to java UUID and routes through createArrayOf uuid`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()

        val a = UUID.parse("11111111-1111-1111-1111-111111111111")
        val b = UUID.parse("22222222-2222-2222-2222-222222222222")
        val value: Array<Any> = arrayOf(a, b)

        val typeName = slot<String>()
        val arrayArg = slot<Array<*>>()
        every { connection.createArrayOf(capture(typeName), capture(arrayArg)) } returns mockk(relaxed = true)

        mapper.bind(Array::class, listOf(UUID::class), stmt, 1, value)

        assertEquals("uuid", typeName.captured)
        // The captured array MUST be `java.util.UUID[]` — the JDBC driver
        // rejects `kotlin.uuid.Uuid` instances, and a regression here means
        // every uuid[] write in the repo silently fails.
        val captured = arrayArg.captured
        assertEquals(2, captured.size)
        assertTrue(captured[0] is java.util.UUID, "first element should be java.util.UUID, got ${captured[0]?.javaClass}")
        assertTrue(captured[1] is java.util.UUID, "second element should be java.util.UUID, got ${captured[1]?.javaClass}")
        // Round-trip the toString() so we know the conversion preserved the
        // bits (not just produced a random new id).
        assertEquals(a.toString(), (captured[0] as java.util.UUID).toString())
        assertEquals(b.toString(), (captured[1] as java.util.UUID).toString())
    }

    @Test
    @OptIn(ExperimentalUuidApi::class)
    fun `bind UUID array passes through java UUID values unchanged`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()

        val a = java.util.UUID.fromString("33333333-3333-3333-3333-333333333333")
        val value: Array<Any> = arrayOf(a)

        val arrayArg = slot<Array<*>>()
        every { connection.createArrayOf(eq("uuid"), capture(arrayArg)) } returns mockk(relaxed = true)

        mapper.bind(Array::class, listOf(UUID::class), stmt, 1, value)

        // Pre-converted java.util.UUID instances pass through untouched.
        assertEquals(1, arrayArg.captured.size)
        assertEquals(a, arrayArg.captured[0])
    }

    @Test
    @OptIn(ExperimentalUuidApi::class)
    fun `bind empty UUID array still routes to createArrayOf uuid`() {
        val mapper = TypeMapper(Array::class)
        val (stmt, connection) = mockStmtWithConnection()
        val value: Array<Any> = emptyArray()

        mapper.bind(Array::class, listOf(UUID::class), stmt, 1, value)

        verify { connection.createArrayOf("uuid", any()) }
    }

    // ── UUID-array map: java.util.UUID[] → kotlin.uuid.Uuid[] for List<UUID> fields ──

    @Test
    @OptIn(ExperimentalUuidApi::class)
    fun `map UUID array converts java UUID values to kotlin Uuid`() {
        val mapper = TypeMapper(Array::class)
        val a = java.util.UUID.fromString("44444444-4444-4444-4444-444444444444")
        val b = java.util.UUID.fromString("55555555-5555-5555-5555-555555555555")
        val sqlArray = mockk<java.sql.Array>(relaxed = true) {
            every { array } returns arrayOf(a, b)
        }
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray(1) } returns sqlArray
        }
        @Suppress("UNCHECKED_CAST")
        val result = mapper.map(Array::class, listOf(UUID::class), rs, 1) as Array<Any>?
        assertNotNull(result)
        assertEquals(2, result.size)
        // Both elements MUST be kotlin.uuid.Uuid so Kotlin-side `equals`,
        // `in`, and set membership against `bosca.serialization.UUID` works.
        assertTrue(result[0] is kotlin.uuid.Uuid, "first element should be kotlin.uuid.Uuid, got ${result[0]::class.qualifiedName}")
        assertTrue(result[1] is kotlin.uuid.Uuid, "second element should be kotlin.uuid.Uuid, got ${result[1]::class.qualifiedName}")
        assertEquals(a.toString(), (result[0] as kotlin.uuid.Uuid).toString())
        assertEquals(b.toString(), (result[1] as kotlin.uuid.Uuid).toString())
    }

    @Test
    @OptIn(ExperimentalUuidApi::class)
    fun `map UUID array preserves equality with kotlin Uuid values`() {
        val mapper = TypeMapper(Array::class)
        val javaUuid = java.util.UUID.fromString("66666666-6666-6666-6666-666666666666")
        val kotlinUuid = UUID.parse("66666666-6666-6666-6666-666666666666")
        val sqlArray = mockk<java.sql.Array>(relaxed = true) {
            every { array } returns arrayOf(javaUuid)
        }
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray(1) } returns sqlArray
        }
        @Suppress("UNCHECKED_CAST")
        val result = mapper.map(Array::class, listOf(UUID::class), rs, 1) as Array<Any>?
        assertNotNull(result)
        // Equality with a kotlin.uuid.Uuid is the regression these conversions
        // exist to prevent — the prior behaviour (raw java.util.UUID instances
        // surfacing through Kotlin generics erasure) silently mis-compared.
        assertEquals(kotlinUuid, result[0])
        assertTrue(kotlinUuid in result.map { it as kotlin.uuid.Uuid })
    }

    @Test
    @OptIn(ExperimentalUuidApi::class)
    fun `map UUID array tolerates pre-converted kotlin Uuid values`() {
        val mapper = TypeMapper(Array::class)
        val kotlinUuid = UUID.parse("77777777-7777-7777-7777-777777777777")
        val sqlArray = mockk<java.sql.Array>(relaxed = true) {
            every { array } returns arrayOf<Any>(kotlinUuid)
        }
        val rs = mockk<ResultSet>(relaxed = true) {
            every { getArray(1) } returns sqlArray
        }
        @Suppress("UNCHECKED_CAST")
        val result = mapper.map(Array::class, listOf(UUID::class), rs, 1) as Array<Any>?
        assertNotNull(result)
        // Already-Kotlin instances should pass through unchanged — defensive
        // coverage for any future driver / proxy that emits the right type.
        assertEquals(kotlinUuid, result[0])
    }

    @Test
    fun `map scalar values by index covers bytes dates null and numeric coercions`() {
        val result = mockk<ResultSet>()
        val bytes = byteArrayOf(1, 2)
        val offset = OffsetDateTime.parse("2026-08-04T12:00:00Z")
        every { result.getBytes(1) } returns bytes
        every { result.getObject(2, OffsetDateTime::class.java) } returns offset
        every { result.getObject(3) } returns null
        every { result.getObject(4) } returns 9L
        every { result.getObject(5) } returns 9
        every { result.getObject(6) } returns 9
        every { result.getObject(7) } returns 9
        every { result.getObject(8) } returns 9
        every { result.getObject(9) } returns 9
        every { result.getObject(10) } returns "plain"

        assertTrue(TypeMapper(ByteArray::class).map(ByteArray::class, emptyList(), result, 1)!!.contentEquals(bytes))
        assertEquals(offset, TypeMapper(OffsetDateTime::class).map(OffsetDateTime::class, emptyList(), result, 2))
        assertNull(TypeMapper(String::class).map(String::class, emptyList(), result, 3))
        assertEquals(9f, TypeMapper(Float::class).map(Float::class, emptyList(), result, 4))
        assertEquals(9.0, TypeMapper(Double::class).map(Double::class, emptyList(), result, 5))
        assertEquals(9, TypeMapper(Int::class).map(Int::class, emptyList(), result, 6))
        assertEquals(9L, TypeMapper(Long::class).map(Long::class, emptyList(), result, 7))
        assertEquals(9.toShort(), TypeMapper(Short::class).map(Short::class, emptyList(), result, 8))
        assertEquals(9.toByte(), TypeMapper(Byte::class).map(Byte::class, emptyList(), result, 9))
        assertEquals("plain", TypeMapper(String::class).map(String::class, emptyList(), result, 10))
    }

    @Test
    fun `map scalar values by name covers bytes local and offset dates and null arrays`() {
        val result = mockk<ResultSet>()
        val bytes = byteArrayOf(3, 4)
        val offset = OffsetDateTime.parse("2026-08-04T12:00:00Z")
        val local = LocalDateTime.parse("2026-08-04T12:00:00")
        every { result.getBytes("bytes") } returns bytes
        every { result.getObject("offset", OffsetDateTime::class.java) } returns offset
        every { result.getObject("local", LocalDateTime::class.java) } returns local
        every { result.getArray("items") } returns null
        every { result.getObject("number") } returns 12L
        every { result.getObject("missing") } returns null
        every { result.getObject("plain") } returns "value"
        every { result.getObject("generic-number") } returns 13

        assertTrue(TypeMapper(ByteArray::class).map(ByteArray::class, emptyList(), result, "bytes")!!.contentEquals(bytes))
        assertEquals(offset, TypeMapper(OffsetDateTime::class).map(OffsetDateTime::class, emptyList(), result, "offset"))
        assertEquals(local, TypeMapper(LocalDateTime::class).map(LocalDateTime::class, emptyList(), result, "local"))
        assertNull(TypeMapper(Array::class).map(Array::class, emptyList(), result, "items"))
        assertEquals(12, TypeMapper(Int::class).map(Int::class, emptyList(), result, "number"))
        assertNull(TypeMapper(String::class).map(String::class, emptyList(), result, "missing"))
        assertEquals("value", TypeMapper(String::class).map(String::class, emptyList(), result, "plain"))
        assertEquals(13, TypeMapper(Number::class).map(Number::class, emptyList(), result, "generic-number"))
    }

    @Test
    fun `map array handles primitive arrays and rejects unknown enum constants`() {
        val primitive = intArrayOf(1, 2)
        val primitiveSqlArray = mockk<java.sql.Array> { every { array } returns primitive }
        val result = mockk<ResultSet> {
            every { getArray(1) } returns primitiveSqlArray
        }
        assertTrue(TypeMapper(IntArray::class).map(IntArray::class, emptyList(), result, 1)!!.contentEquals(primitive))

        val enumSqlArray = mockk<java.sql.Array> { every { array } returns arrayOf("purple") }
        every { result.getArray(2) } returns enumSqlArray
        assertFailsWith<IllegalStateException> {
            TypeMapper(Array::class).map(Array::class, listOf(Color::class), result, 2)
        }
    }

    @Test
    fun `bind null values selects the remaining SQL types`() {
        val statement = mockk<PreparedStatement>(relaxed = true)
        TypeMapper(BigDecimal::class).bind(BigDecimal::class, emptyList(), statement, 1, null)
        TypeMapper(java.util.Date::class).bind(java.util.Date::class, emptyList(), statement, 2, null)
        TypeMapper(Time::class).bind(Time::class, emptyList(), statement, 3, null)
        TypeMapper(Timestamp::class).bind(Timestamp::class, emptyList(), statement, 4, null)
        TypeMapper(ByteArray::class).bind(ByteArray::class, emptyList(), statement, 5, null)
        TypeMapper(Any::class).bind(Any::class, emptyList(), statement, 6, null)

        verify { statement.setNull(1, Types.DECIMAL) }
        verify { statement.setNull(2, Types.DATE) }
        verify { statement.setNull(3, Types.TIME) }
        verify { statement.setNull(4, Types.TIMESTAMP) }
        verify { statement.setNull(5, Types.VARBINARY) }
        verify { statement.setNull(6, Types.OTHER) }
    }

    @Test
    fun `bind unsupported array element falls back to setObject`() {
        val mapper = TypeMapper(Array::class)
        val statement = mockk<PreparedStatement>(relaxed = true)
        val value = arrayOf(1.5)
        @Suppress("UNCHECKED_CAST")
        mapper.bind(Array::class, listOf(Double::class), statement, 1, value as Array<Any>)
        verify { statement.setObject(1, value) }
    }

    @Test
    fun `SQL type resolution accepts primitive classes and a null class`() {
        val mapper = TypeMapper(Any::class)
        val method = TypeMapper::class.java.getDeclaredMethod("getSqlType", Class::class.java).apply {
            isAccessible = true
        }

        assertEquals(Types.NULL, method.invoke(mapper, null))
        assertEquals(Types.INTEGER, method.invoke(mapper, Int::class.javaPrimitiveType))
        assertEquals(Types.BIGINT, method.invoke(mapper, Long::class.javaPrimitiveType))
        assertEquals(Types.BOOLEAN, method.invoke(mapper, Boolean::class.javaPrimitiveType))
        assertEquals(Types.DOUBLE, method.invoke(mapper, Double::class.javaPrimitiveType))
        assertEquals(Types.FLOAT, method.invoke(mapper, Float::class.javaPrimitiveType))
        assertEquals(Types.SMALLINT, method.invoke(mapper, Short::class.javaPrimitiveType))
        assertEquals(Types.TINYINT, method.invoke(mapper, Byte::class.javaPrimitiveType))
        assertEquals(Types.OTHER, method.invoke(mapper, emptyArray<Any>().javaClass))
    }
}
