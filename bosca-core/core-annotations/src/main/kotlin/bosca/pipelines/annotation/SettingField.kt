package bosca.pipelines.annotation

/**
 * One sub-field of a [SettingControl.GROUP_LIST] setting — the shape of each object row (e.g. a Switch
 * case's `label` and `expression`). It carries the same scalar declarations as a [SettingSlot] but
 * **cannot itself be a group**: an annotation element type may not be cyclic (`SettingSlot.fields`
 * referencing `Array<SettingSlot>` is a compile error), so the row fields are a distinct, non-nesting
 * annotation. No existing node nests a group inside a group, so this is sufficient.
 *
 * Used only as a nested value of [SettingSlot.fields]; never applied to a declaration directly.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingField(
    val name: String,
    val control: SettingControl,
    val label: String = "",
    val description: String = "",
    val placeholder: String = "",
    val default: String = "",
    val required: Boolean = false,
    val secret: Boolean = false,
    val mono: Boolean = false,
    val language: String = "",
    val reference: ReferenceSource = ReferenceSource.NONE,
    val options: Array<SettingOption> = [],
)
