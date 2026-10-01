package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Element = Types.StructType.of(
    Types.NestedField.required(171, "id", Types.StringType.get()),
    Types.NestedField.required(172, "type", Types.StringType.get()),
    Types.NestedField.required(173, "content", Types.ListType.ofRequired(1730, Content)),
    Types.NestedField.required(174, "extras", Types.StringType.get())
)
