package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Content = Types.StructType.of(
    Types.NestedField.required(1731, "id", Types.StringType.get()),
    Types.NestedField.required(1732, "type", Types.StringType.get()),
    Types.NestedField.optional(1733, "index", Types.LongType.get()),
    Types.NestedField.optional(1734, "percent", Types.DoubleType.get())
)