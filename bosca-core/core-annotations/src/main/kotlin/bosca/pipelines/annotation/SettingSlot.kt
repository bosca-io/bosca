package bosca.pipelines.annotation

/**
 * Declares one editable setting of a `@PipelineNodeType` — the settings-side mirror of [InputSlot].
 * Each slot names a settings key ([name], the key written into the node's graph JSON) and the editor
 * [control] that renders it; the Studio inspector iterates a node type's declared settings and renders
 * each one generically, so a node type's whole form is data-driven and adding a node type (including a
 * contributed/integration one) needs no UI change.
 *
 * Modifiers shape the control: [label] (blank → the renderer humanizes [name]), [description] (a hint),
 * [placeholder], [required], [secret] (mask a TEXT), [mono] (monospace), [language] (a CODE editor's
 * language; "jsonata" enables the live preview), and [default] — the node's own default, **as a
 * string** (e.g. `"true"`, `"100"`, `"contacts"`). The editor stores a value only when it differs from
 * [default] (and omits an empty optional), so the node's compiled-in default re-applies on absence
 * (kotlinx `encodeDefaults = false`); [default] also seeds a freshly-added node and shows as the
 * placeholder.
 *
 * [options] supplies the choices of an [SettingControl.ENUM] (and literal extras for a
 * [SettingControl.REFERENCE] picker). [reference] names the server list a REFERENCE picks from. [fields]
 * declares the row shape of a [SettingControl.GROUP_LIST] (with [itemLabel] naming a row). [group] is an
 * optional section header to render the field under (e.g. "Index only when"). [visibleWhenSetting] +
 * [visibleWhenEquals] hide the field unless another setting's *effective* value (its stored value, or
 * its [default]) equals the given string — e.g. a timeout shown only when an `awaitCompletion` toggle is on.
 *
 * Used only as a nested value of [PipelineNodeType.settings]; never applied to a declaration directly.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingSlot(
    val name: String,
    val control: SettingControl,
    val label: String = "",
    val description: String = "",
    val placeholder: String = "",
    /** The node's own default, string-encoded; drives omit-on-default, the new-node seed, and the placeholder. */
    val default: String = "",
    val required: Boolean = false,
    /** Mask a [SettingControl.TEXT] as a password (e.g. a signing secret). */
    val secret: Boolean = false,
    /** Render the field monospace (dot-paths, ids, code-ish text). */
    val mono: Boolean = false,
    /** A [SettingControl.CODE] editor's language; "jsonata" enables the live-preview workbench. */
    val language: String = "",
    /** The server list a [SettingControl.REFERENCE] picks from; [ReferenceSource.NONE] = not a reference. */
    val reference: ReferenceSource = ReferenceSource.NONE,
    /** Choices for an [SettingControl.ENUM], or literal extras prepended to a REFERENCE picker. */
    val options: Array<SettingOption> = [],
    /** Sub-fields of a [SettingControl.GROUP_LIST] row. */
    val fields: Array<SettingField> = [],
    /** Singular row label for a [SettingControl.GROUP_LIST] (e.g. "Case"); `""` = generic. */
    val itemLabel: String = "",
    /** Optional section header to group the field under in the inspector; `""` = no header. */
    val group: String = "",
    /** Show this field only when [visibleWhenSetting]'s effective value equals [visibleWhenEquals]; `""` = always. */
    val visibleWhenSetting: String = "",
    val visibleWhenEquals: String = "",
)
