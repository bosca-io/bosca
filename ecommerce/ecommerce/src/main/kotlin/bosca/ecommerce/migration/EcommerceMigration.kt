package bosca.ecommerce.migration

import bosca.db.migrations.Migration

/**
 * Flyway migrations for the `ecom` Postgres schema.
 *
 * Two silent-failure traps this module deliberately avoids:
 *  - The registering `@Provider` MUST be named (see [bosca.ecommerce.configuration.Configuration]) —
 *    an unnamed provider clobbers the `Migration` type slot and no migrations run.
 *  - Every `.sql` file MUST be listed in [resources] — an unlisted file simply never runs.
 */
class EcommerceMigration : Migration {

    override val schema: String = "ecom"

    override val resources: List<String> = listOf(
        "V1__ecom.sql",
        "V2__shipments.sql",
        "V3__container_admin.sql",
        "V4__product_dimensions.sql",
        "V5__shipment_container.sql",
        "V6__shipment_label.sql",
        "V7__company_units.sql",
        "V8__shipment_tracking.sql",
        "V9__shipment_parcels.sql",
        "V10__shipment_unpackable.sql",
        "V11__shipment_unpackable_backfill.sql",
        "V12__currency.sql",
        "V13__catalog_currency.sql",
        "V14__returns.sql",
        "V15__indexes.sql",
        "V16__promotion_account_limits.sql",
        "V17__iap.sql",
        "V18__inventory_sync.sql",
    )
}
