package bosca.pipelines.annotation

/**
 * One choice of a [SettingControl.ENUM] setting, or a literal extra prepended to a
 * [SettingControl.REFERENCE] picker (e.g. the Input node's `JSON` choice alongside the event list, or
 * a `(none)` row). [value] is what's stored in the node's settings; [label] is shown to the builder
 * (`""` falls back to [value]).
 *
 * Used only as a nested value of [SettingSlot.options] / [SettingField.options]; never applied to a
 * declaration directly.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingOption(
    val value: String,
    val label: String = "",
)
