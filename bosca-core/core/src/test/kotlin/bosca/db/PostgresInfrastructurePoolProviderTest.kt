package bosca.db

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresInfrastructurePoolProviderTest {

    private fun app(yaml: String) = BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))

    private val main = """
        database:
          postgres:
            url: "jdbc:postgresql://main/bosca"
            user: "bosca"
            password: "secret"
            maxConnections: 50
    """.trimIndent()

    @Test fun `exists only when a PostgreSQL infrastructure backend is selected`() {
        assertFalse(PostgresInfrastructurePoolProvider(app(main)).exists)
        assertFalse(PostgresInfrastructurePoolProvider(app("$main\ncache:\n  type: redis")).exists)
        for (selector in PostgresInfrastructurePoolProvider.BACKEND_SELECTORS) {
            val (section, key) = selector.split('.')
            assertTrue(PostgresInfrastructurePoolProvider(app("$main\n$section:\n  $key: postgres")).exists, selector)
        }
    }

    @Test fun `main database settings are reused with the infrastructure connection limit`() {
        val configuration = PostgresInfrastructurePoolProvider(app(main)).configuration()
        assertEquals(ConnectionConfig("jdbc:postgresql://main/bosca", "bosca", "secret", maxConnections = 10), configuration)
    }

    @Test fun `the dedicated block sizes the pool but cannot select another database`() {
        val dedicated = main + "\n" + listOf(
            "  postgres-infrastructure:",
            "    url: \"jdbc:postgresql://infrastructure/bosca\"",
            "    user: \"infra\"",
            "    password: \"other\"",
            "    maxConnections: 4",
        ).joinToString("\n")
        assertEquals(
            ConnectionConfig("jdbc:postgresql://main/bosca", "bosca", "secret", maxConnections = 4),
            PostgresInfrastructurePoolProvider(app(dedicated)).configuration(),
        )
        val sizeOnly = main + "\n  postgres-infrastructure:\n    maxConnections: 3"
        assertEquals(
            ConnectionConfig("jdbc:postgresql://main/bosca", "bosca", "secret", maxConnections = 3),
            PostgresInfrastructurePoolProvider(app(sizeOnly)).configuration(),
        )
    }
}
