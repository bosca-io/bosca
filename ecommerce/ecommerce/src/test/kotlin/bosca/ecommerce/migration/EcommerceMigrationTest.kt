package bosca.ecommerce.migration

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * acceptance: applying the full `ecom` migration set (V1–V16) creates the schema with every
 * table, the native enum types and their later-added values, the moved currency anchor, physical
 * product dimensions, the `cart_items` reporting view, and the expected indexes. Flyway applies every
 * `V*.sql` in the module's migrations dir against a throwaway Postgres via Testcontainers (Docker
 * required), so a broken later migration is caught here — not only when it fails outright.
 */
class EcommerceMigrationTest {

    @Test
    fun `migration applies the ecom schema with tables, enums, view, and indexes`() {
        SharedPostgreSQLContainer("pgvector/pgvector:pg18").use { pg ->
            pg.start()

            // Load ONLY this module's migrations: a blanket `classpath:db/migrations` scan would
            // collide with other modules' V1 files (e.g. core's V1__initialize.sql) on the test
            // classpath. The platform's Migration loader avoids this by loading an explicit
            // per-module resources list into the module's own schema; here we resolve this module's
            // own resources directory from the classloader so only V1__ecom.sql is seen.
            val migrationsDir = File(
                javaClass.classLoader.getResource("db/migrations/V1__ecom.sql")!!.toURI(),
            ).parentFile

            Flyway.configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .schemas("ecom")
                .locations("filesystem:${migrationsDir.absolutePath}")
                .load()
                .migrate()

            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                assertTrue(schemaExists(c), "ecom schema should exist")

                val tables = tables(c)
                val expected = listOf(
                    "audit", "companies", "customers", "accounts", "account_customers",
                    "account_addresses", "company_credits", "catalogs", "manufacturers",
                    "products", "catalog_products", "payment_providers", "shipping_providers",
                    "stores", "fulfillment_centers", "product_inventory", "shipping_containers",
                    "carts", "cart_addresses", "payments", "subscription_plan_groups",
                    "subscription_plans", "subscriptions", "promotions", "promotion_availability",
                    "promotion_redemptions", "account_promotions",
                    // Added by later migrations (V2 shipments, V14 returns, V17 iap) — asserted so a broken
                    // later migration is caught here, not only when it fails outright.
                    "shipments", "returns", "iap_transactions",
                )
                expected.forEach { assertTrue(it in tables, "ecom.$it should exist (have: $tables)") }

                val productTypeLabels = enumLabels(c, "product_type")
                assertTrue("subscription" in productTypeLabels, "product_type should include the new 'subscription' value")
                assertTrue("physical" in productTypeLabels, "product_type should include 'physical'")

                val paymentTypeLabels = enumLabels(c, "payment_type")
                assertTrue("company_credit" in paymentTypeLabels, "payment_type should include 'company_credit'")

                // Shipment/return lifecycle enums added across V2/V8/V10/V14.
                val shipmentStatusLabels = enumLabels(c, "shipment_status")
                assertTrue("unable_to_package" in shipmentStatusLabels, "shipment_status should include 'unable_to_package' (V10)")
                assertTrue("in_transit" in shipmentStatusLabels, "shipment_status should include 'in_transit' (V8)")
                assertTrue(enumLabels(c, "return_status").isNotEmpty(), "return_status enum should exist (V14)")

                // Per-account promotion redemption limit + frequency window (V16).
                assertTrue("forever" in enumLabels(c, "frequency_limit"), "frequency_limit enum should include 'forever' (V16)")
                assertTrue("per_account_limit" in columns(c, "promotions"), "promotions.per_account_limit should exist (V16)")
                assertTrue("frequency_limit" in columns(c, "promotions"), "promotions.frequency_limit should exist (V16)")

                // Currency anchor moved from stores -> catalogs (V12 add, V13 catalog + stores drop).
                assertTrue("currency" in columns(c, "catalogs"), "catalogs.currency should exist (V13)")
                assertTrue("currency" !in columns(c, "stores"), "stores.currency should have been dropped (V13)")

                // Physical product dimensions (V4).
                val productColumns = columns(c, "products")
                assertTrue(listOf("width", "height", "length").all { it in productColumns }, "products should carry width/height/length (V4); have: $productColumns")

                assertTrue(viewExists(c), "ecom.cart_items view should exist")

                val indexes = indexes(c)
                assertTrue("audit_entity_idx" in indexes, "audit_entity_idx should exist")
                assertTrue("subscriptions_renews_idx" in indexes, "subscriptions_renews_idx should exist")
                assertTrue("catalog_products_catalog_idx" in indexes, "catalog_products_catalog_idx should exist")
                // V15 perf indexes.
                assertTrue("payments_created_idx" in indexes, "payments_created_idx should exist (V15)")
                assertTrue("shipments_unpackable_by_company_idx" in indexes, "shipments_unpackable_by_company_idx should exist (V15)")
                // V16 per-account redemption index.
                assertTrue("promotion_redemptions_promo_account_idx" in indexes, "promotion_redemptions_promo_account_idx should exist (V16)")

                // V17 IAP entitlement ledger: platform enum, dedupe table + unique index, and the
                // external-billing flag on subscriptions.
                val iapPlatformLabels = enumLabels(c, "iap_platform")
                assertTrue("android" in iapPlatformLabels, "iap_platform should include 'android' (V17)")
                assertTrue("ios" in iapPlatformLabels, "iap_platform should include 'ios' (V17)")
                assertTrue("external" in columns(c, "subscriptions"), "subscriptions.external should exist (V17)")
                assertTrue("iap_transactions_platform_tx_idx" in indexes, "iap_transactions_platform_tx_idx should exist (V17)")

                // V18 inventory-sync bookkeeping.
                assertTrue("last_synced" in columns(c, "fulfillment_centers"), "fulfillment_centers.last_synced should exist (V18)")
            }
        }
    }

    private fun schemaExists(c: Connection): Boolean =
        c.prepareStatement("select 1 from information_schema.schemata where schema_name = 'ecom'")
            .use { it.executeQuery().use { rs -> rs.next() } }

    private fun tables(c: Connection): Set<String> = buildSet {
        c.prepareStatement(
            "select table_name from information_schema.tables " +
                "where table_schema = 'ecom' and table_type = 'BASE TABLE'",
        ).use { it.executeQuery().use { rs -> while (rs.next()) add(rs.getString(1)) } }
    }

    private fun columns(c: Connection, table: String): Set<String> = buildSet {
        c.prepareStatement(
            "select column_name from information_schema.columns where table_schema = 'ecom' and table_name = ?",
        ).use { it.setString(1, table); it.executeQuery().use { rs -> while (rs.next()) add(rs.getString(1)) } }
    }

    private fun viewExists(c: Connection): Boolean =
        c.prepareStatement(
            "select 1 from information_schema.views where table_schema = 'ecom' and table_name = 'cart_items'",
        ).use { it.executeQuery().use { rs -> rs.next() } }

    private fun enumLabels(c: Connection, typeName: String): Set<String> = buildSet {
        c.prepareStatement(
            "select e.enumlabel from pg_enum e " +
                "join pg_type t on t.oid = e.enumtypid " +
                "join pg_namespace n on n.oid = t.typnamespace " +
                "where n.nspname = 'ecom' and t.typname = ?",
        ).use { it.setString(1, typeName); it.executeQuery().use { rs -> while (rs.next()) add(rs.getString(1)) } }
    }

    private fun indexes(c: Connection): Set<String> = buildSet {
        c.prepareStatement("select indexname from pg_indexes where schemaname = 'ecom'")
            .use { it.executeQuery().use { rs -> while (rs.next()) add(rs.getString(1)) } }
    }
}
