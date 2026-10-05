package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Context = Types.StructType.of(
    Types.NestedField.required(101, "app_id", Types.StringType.get()),
    Types.NestedField.required(102, "app_version", Types.StringType.get()),
    Types.NestedField.optional(103, "browser", Browser),
    Types.NestedField.required(105, "device", Device),
    Types.NestedField.optional(106, "geo", Geo),
    Types.NestedField.required(107, "session_id", Types.StringType.get()),
    Types.NestedField.optional(108, "user_id", Types.StringType.get())
)