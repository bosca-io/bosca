package bosca.analytics.iceberg

import org.apache.iceberg.Schema
import org.apache.iceberg.types.Types

internal val EventSchema: Schema = Schema(
    Types.NestedField.required(1, "id", Types.UUIDType.get()),
    Types.NestedField.optional(2, "client_id", Types.StringType.get()),
    Types.NestedField.required(3, "type", Types.StringType.get()),
    Types.NestedField.required(4, "sent", Types.TimestampType.withoutZone()),
    Types.NestedField.required(5, "sent_micros", Types.LongType.get()),
    Types.NestedField.required(6, "received", Types.TimestampType.withoutZone()),
    Types.NestedField.required(7, "received_micros", Types.LongType.get()),
    Types.NestedField.required(8, "created", Types.TimestampType.withoutZone()),
    Types.NestedField.required(9, "created_micros", Types.LongType.get()),
    Types.NestedField.optional(10, "context", Context),
    Types.NestedField.optional(11, "element", Element),
    Types.NestedField.optional(12, "error", Error),
    // Page lives in a fresh 2000-series field ID block; see Page.kt for the
    // rationale and the rule against reusing IDs across schema evolutions.
    Types.NestedField.optional(2000, "page", Page),
)
