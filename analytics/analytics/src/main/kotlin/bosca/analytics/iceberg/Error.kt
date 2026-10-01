package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Error = Types.StructType.of(
    Types.NestedField.required(181, "message", Types.StringType.get()),
    Types.NestedField.optional(182, "type", Types.StringType.get()),
    Types.NestedField.optional(183, "stack_trace", Types.StringType.get()),
    Types.NestedField.required(184, "fatal", Types.BooleanType.get()),
    Types.NestedField.optional(185, "code", Types.StringType.get()),
    Types.NestedField.optional(186, "fingerprint", Types.StringType.get()),
    Types.NestedField.optional(187, "context_json", Types.StringType.get())
)
