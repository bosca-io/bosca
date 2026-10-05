package bosca.postgres.admin.pgbouncer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [PgBouncerClient] correctly derives the admin console URL
 * from a primary database JDBC URL by replacing the database name with
 * `pgbouncer` and adding a connect timeout.
 */
class PgBouncerClientTest {

    @Test
    fun `deriveAdminUrl replaces database name with pgbouncer`() {
        val result = PgBouncerClient.deriveAdminUrl("jdbc:postgresql://localhost:5432/bosca")
        assertEquals("jdbc:postgresql://localhost:5432/pgbouncer?connectTimeout=3", result)
    }

    @Test
    fun `deriveAdminUrl preserves existing query parameters`() {
        val result = PgBouncerClient.deriveAdminUrl("jdbc:postgresql://host:5432/mydb?sslmode=require")
        assertEquals("jdbc:postgresql://host:5432/pgbouncer?sslmode=require&connectTimeout=3", result)
    }

    @Test
    fun `deriveAdminUrl preserves multiple query parameters`() {
        val result = PgBouncerClient.deriveAdminUrl("jdbc:postgresql://host:5432/mydb?sslmode=require&sslrootcert=/path/cert.pem")
        assertEquals("jdbc:postgresql://host:5432/pgbouncer?sslmode=require&sslrootcert=/path/cert.pem&connectTimeout=3", result)
    }

    @Test
    fun `deriveAdminUrl overrides existing connectTimeout`() {
        val result = PgBouncerClient.deriveAdminUrl("jdbc:postgresql://host:5432/mydb?connectTimeout=30")
        assertEquals("jdbc:postgresql://host:5432/pgbouncer?connectTimeout=3", result)
    }

    @Test
    fun `deriveAdminUrl handles URL without database`() {
        val result = PgBouncerClient.deriveAdminUrl("jdbc:postgresql://host:5432")
        assertEquals("jdbc:postgresql://host:5432/pgbouncer?connectTimeout=3", result)
    }

    @Test
    fun `deriveAdminUrl preserves host and port`() {
        val result = PgBouncerClient.deriveAdminUrl("jdbc:postgresql://10.0.0.1:6432/production")
        assertEquals("jdbc:postgresql://10.0.0.1:6432/pgbouncer?connectTimeout=3", result)
    }
}
