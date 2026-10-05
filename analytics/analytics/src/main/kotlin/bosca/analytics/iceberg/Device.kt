package bosca.analytics.iceberg

import org.apache.iceberg.types.Types

internal val Device = Types.StructType.of(
    Types.NestedField.required(1051, "installation_id", Types.StringType.get()),
    Types.NestedField.required(1052, "manufacturer", Types.StringType.get()),
    Types.NestedField.required(1053, "model", Types.StringType.get()),
    Types.NestedField.required(1054, "platform", Types.StringType.get()),
    Types.NestedField.required(1055, "primary_locale", Types.StringType.get()),
    Types.NestedField.required(1056, "system_name", Types.StringType.get()),
    Types.NestedField.required(1057, "timezone", Types.StringType.get()),
    Types.NestedField.required(1058, "type", Types.StringType.get()),
    Types.NestedField.required(1059, "version", Types.StringType.get())
)