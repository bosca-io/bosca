@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.db.mapper

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [TypeMapper]'s `uuid[]` bind / map paths.
 *
 * `TypeMapper` is depended on by every JDBC repository in the
 * codebase, so these conversions need to round-trip correctly
 * against a real PostgreSQL — not just a mock. Every test here
 * pushes a `kotlin.uuid.Uuid[]` through `TypeMapper.bind`, runs
 * the SQL, reads the row back through `TypeMapper.map`, and
 * asserts the values come out as `kotlin.uuid.Uuid` instances
 * that compare equal to the inputs.
 *
 * Without these conversions, a `List<UUID>` field deserialized
 * from a `uuid[]` column would surface as `java.util.UUID`
 * instances under generics erasure — every Kotlin-side `equals`,
 * `in`, or set-membership check against `bosca.serialization.UUID`
 * would silently mis-compare.
 */
class TypeMapperUuidArrayEndToEndTest {

    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setup() {
        postgres = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        postgres.start()

        pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 4,
                ),
                key = "type-mapper-uuid-array-test",
            )
        )
        runE2E {
            connection().useStatement(
                "create table if not exists tm_uuid_array_test (id integer primary key, ids uuid[] not null)"
            ) { it.execute() }
            connection().useStatement("delete from tm_uuid_array_test") { it.execute() }
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@TypeMapperUuidArrayEndToEndTest::pool.isInitialized) pool.close()
        if (this@TypeMapperUuidArrayEndToEndTest::postgres.isInitialized) postgres.stop()
    }

    private fun runE2E(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    @Test
    fun `kotlin Uuid array round-trips through uuid array column`() = runE2E {
        val a = UUID.parse("11111111-1111-1111-1111-111111111111")
        val b = UUID.parse("22222222-2222-2222-2222-222222222222")
        val c = UUID.parse("33333333-3333-3333-3333-333333333333")
        val payload: Array<Any> = arrayOf(a, b, c)

        val mapper = TypeMapper(Array::class)
        connection().useStatement(
            "insert into tm_uuid_array_test (id, ids) values (?, ?)"
        ) { stmt ->
            stmt.setInt(1, 1)
            mapper.bind(Array::class, listOf(UUID::class), stmt, 2, payload)
            stmt.execute()
        }

        connection().useStatement(
            "select ids from tm_uuid_array_test where id = 1"
        ) { stmt ->
            val rs = stmt.executeQuery()
            assertTrue(rs.next(), "row should exist after insert")

            @Suppress("UNCHECKED_CAST")
            val out = mapper.map(Array::class, listOf(UUID::class), rs, 1) as Array<Any>?
            assertNotNull(out, "uuid[] map result should not be null")
            assertEquals(3, out.size)
            // Critical invariant — the Kotlin type is preserved end-to-end.
            for (item in out) {
                assertTrue(
                    item is kotlin.uuid.Uuid,
                    "round-trip element must be kotlin.uuid.Uuid, got ${item::class.qualifiedName}"
                )
            }
            // Ordering and value-equality both hold against the original Kotlin Uuids.
            assertContentEquals(arrayOf(a, b, c), out)
            // And membership lookups Just Work — the original regression vector.
            assertTrue(a in out.map { it as kotlin.uuid.Uuid })
            assertTrue(b in out.map { it as kotlin.uuid.Uuid })
        }
    }

    @Test
    fun `empty uuid array round-trips`() = runE2E {
        val mapper = TypeMapper(Array::class)
        connection().useStatement(
            "insert into tm_uuid_array_test (id, ids) values (?, ?)"
        ) { stmt ->
            stmt.setInt(1, 2)
            mapper.bind(Array::class, listOf(UUID::class), stmt, 2, emptyArray<Any>())
            stmt.execute()
        }

        connection().useStatement(
            "select ids from tm_uuid_array_test where id = 2"
        ) { stmt ->
            val rs = stmt.executeQuery()
            assertTrue(rs.next())
            @Suppress("UNCHECKED_CAST")
            val out = mapper.map(Array::class, listOf(UUID::class), rs, 1) as Array<Any>?
            assertNotNull(out)
            assertEquals(0, out.size)
        }
    }

    @Test
    fun `single uuid round-trips`() = runE2E {
        val only = UUID.parse("44444444-4444-4444-4444-444444444444")
        val mapper = TypeMapper(Array::class)
        connection().useStatement(
            "insert into tm_uuid_array_test (id, ids) values (?, ?)"
        ) { stmt ->
            stmt.setInt(1, 3)
            mapper.bind(Array::class, listOf(UUID::class), stmt, 2, arrayOf<Any>(only))
            stmt.execute()
        }

        connection().useStatement(
            "select ids from tm_uuid_array_test where id = 3"
        ) { stmt ->
            val rs = stmt.executeQuery()
            assertTrue(rs.next())
            @Suppress("UNCHECKED_CAST")
            val out = mapper.map(Array::class, listOf(UUID::class), rs, 1) as Array<Any>?
            assertNotNull(out)
            assertEquals(1, out.size)
            assertEquals(only, out[0])
            assertTrue(out[0] is kotlin.uuid.Uuid)
        }
    }

    @Test
    fun `array containment query works after round-trip`() = runE2E {
        // Pins both the bind side (storing kotlin.uuid.Uuid through TypeMapper)
        // and a follow-up @> overlap predicate against the same uuid — proves
        // the bytes hit Postgres in the canonical uuid encoding.
        val a = UUID.parse("55555555-5555-5555-5555-555555555555")
        val b = UUID.parse("66666666-6666-6666-6666-666666666666")
        val mapper = TypeMapper(Array::class)
        connection().useStatement(
            "insert into tm_uuid_array_test (id, ids) values (?, ?)"
        ) { stmt ->
            stmt.setInt(1, 4)
            mapper.bind(Array::class, listOf(UUID::class), stmt, 2, arrayOf<Any>(a, b))
            stmt.execute()
        }

        connection().useStatement(
            "select count(*) from tm_uuid_array_test where id = 4 and ids @> array[?]::uuid[]"
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(a.toString()))
            val rs = stmt.executeQuery()
            assertTrue(rs.next())
            assertEquals(
                1, rs.getInt(1),
                "the previously-inserted uuid should be findable via @>"
            )
        }
    }

    @Test
    fun `map by name path also returns kotlin Uuid`() = runE2E {
        val a = UUID.parse("77777777-7777-7777-7777-777777777777")
        val b = UUID.parse("88888888-8888-8888-8888-888888888888")
        val mapper = TypeMapper(Array::class)
        connection().useStatement(
            "insert into tm_uuid_array_test (id, ids) values (?, ?)"
        ) { stmt ->
            stmt.setInt(1, 5)
            mapper.bind(Array::class, listOf(UUID::class), stmt, 2, arrayOf<Any>(a, b))
            stmt.execute()
        }

        connection().useStatement(
            "select ids as my_ids from tm_uuid_array_test where id = 5"
        ) { stmt ->
            val rs = stmt.executeQuery()
            assertTrue(rs.next())
            @Suppress("UNCHECKED_CAST")
            val out = mapper.map(Array::class, listOf(UUID::class), rs, "my_ids") as Array<Any>?
            assertNotNull(out)
            assertEquals(2, out.size)
            assertTrue(out[0] is kotlin.uuid.Uuid)
            assertTrue(out[1] is kotlin.uuid.Uuid)
            assertContentEquals(arrayOf(a, b), out)
        }
    }
}
