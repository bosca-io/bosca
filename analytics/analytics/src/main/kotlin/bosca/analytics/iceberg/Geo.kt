package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Geo = Types.StructType.of(
    Types.NestedField.optional(1061, "city", Types.StringType.get()),
    Types.NestedField.optional(1062, "country", Types.StringType.get()),
    Types.NestedField.optional(1063, "continent", Types.StringType.get()),
    Types.NestedField.optional(1064, "longitude", Types.DoubleType.get()),
    Types.NestedField.optional(1065, "latitude", Types.DoubleType.get()),
    Types.NestedField.optional(1066, "region", Types.StringType.get()),
    Types.NestedField.optional(1067, "region_code", Types.StringType.get()),
    Types.NestedField.optional(1068, "postal_code", Types.StringType.get()),
    Types.NestedField.optional(1069, "timezone", Types.StringType.get())
)