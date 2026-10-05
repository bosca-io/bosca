package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Browser = Types.StructType.of(
    Types.NestedField.required(1031, "agent", Types.StringType.get())
)