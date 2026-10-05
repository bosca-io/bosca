package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

/**
 * Iceberg struct schema for the top-level `page` field on the events table.
 *
 * Field IDs are drawn from a fresh 2000-series block to avoid colliding with
 * any existing column in [EventSchema], [Element], [Content], [Device],
 * [Error], or [Context] (current max in those structs is in the 1700s).
 * **Never reuse a field ID** — Iceberg identifies columns by ID across
 * schema evolutions, so reusing one would silently re-interpret old data.
 *
 * The struct itself uses ID 2000, and its three child fields use 2001-2003.
 * All fields are optional so legacy events written before the column existed
 * (and events from non-browser SDKs that have no page context) read back as
 * null without crashing.
 */
internal val Page = Types.StructType.of(
    Types.NestedField.optional(2001, "path", Types.StringType.get()),
    Types.NestedField.optional(2002, "url", Types.StringType.get()),
    Types.NestedField.optional(2003, "title", Types.StringType.get()),
)
