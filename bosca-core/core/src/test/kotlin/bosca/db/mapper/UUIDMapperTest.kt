package bosca.db.mapper

import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.toJavaUuid

class UUIDMapperTest {

    private val mapper = UUIDMapper()

    @Test
    fun `map by index returns UUID when result has value`() {
        val uuid = UUID.random()
        val resultSet = mockk<ResultSet> {
            every { getString(1) } returns uuid.toString()
        }

        val result = mapper.map(UUID::class, emptyList(), resultSet, 1)
        assertEquals(uuid, result)
    }

    @Test
    fun `map by index returns null when result is null`() {
        val resultSet = mockk<ResultSet> {
            every { getString(1) } returns null
        }

        val result = mapper.map(UUID::class, emptyList(), resultSet, 1)
        assertNull(result)
    }

    @Test
    fun `map by name returns UUID when result has value`() {
        val uuid = UUID.random()
        val resultSet = mockk<ResultSet> {
            every { getString("id") } returns uuid.toString()
        }

        val result = mapper.map(UUID::class, emptyList(), resultSet, "id")
        assertEquals(uuid, result)
    }

    @Test
    fun `map by name returns null when result is null`() {
        val resultSet = mockk<ResultSet> {
            every { getString("id") } returns null
        }

        val result = mapper.map(UUID::class, emptyList(), resultSet, "id")
        assertNull(result)
    }

    @Test
    fun `bind sets UUID value on prepared statement`() {
        val uuid = UUID.random()
        val stmt = mockk<PreparedStatement>(relaxed = true)

        val result = mapper.bind(UUID::class, emptyList(), stmt, 1, uuid)

        verify { stmt.setObject(1, uuid.toJavaUuid()) }
        assertEquals(stmt, result)
    }

    @Test
    fun `bind sets null when value is null`() {
        val stmt = mockk<PreparedStatement>(relaxed = true)

        val result = mapper.bind(UUID::class, emptyList(), stmt, 1, null)

        verify { stmt.setObject(1, null) }
        assertEquals(stmt, result)
    }
}
