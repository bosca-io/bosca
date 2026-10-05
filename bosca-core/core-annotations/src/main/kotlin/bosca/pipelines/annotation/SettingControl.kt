package bosca.pipelines.annotation

/**
 * The editor control a node's setting renders as — the settings counterpart of [SlotKind] for ports.
 * A `@SettingSlot` names one control; the Studio inspector maps it to a shared form widget, so a node
 * type's whole form is data-driven (no hardcoded per-node-kind form in the UI).
 *
 *  - [TEXT]/[TEXTAREA] — a single- or multi-line string.
 *  - [INTEGER]/[NUMBER] — a whole number / a decimal; stored only when positive, else the key is
 *    omitted so the node's own default applies (matching the editor's existing numeric-field rule).
 *  - [BOOLEAN] — a toggle; stored only when it differs from the declared `default`, else omitted.
 *  - [ENUM] — one of a fixed `options` set.
 *  - [CODE] — a code editor in `language` ("jsonata" turns on the live-preview workbench).
 *  - [SCHEMA] — the JSON-Schema-subset builder (Input/Output contracts, a JSONata result schema).
 *  - [CONDITION] — the JSONata condition builder (the Condition node).
 *  - [REFERENCE] — a picker over a server-loaded list named by [SettingSlot.reference]
 *    (jobs, scripts, pipelines, events, attribute types, search indexes).
 *  - [LIST] — a comma-separated list of strings (recipients, content types).
 *  - [GROUP_LIST] — an ordered, add/remove/reorder list of objects whose sub-fields are declared in
 *    [SettingSlot.fields] (the Switch node's cases).
 *
 * Lives in `core-annotations` so `@SettingSlot` can reference it directly; `core-pipelines` reuses it
 * verbatim in `NodeSettingSlot`/`NodeDescriptor`.
 */
enum class SettingControl {
    TEXT, TEXTAREA, INTEGER, NUMBER, BOOLEAN, ENUM, CODE, SCHEMA, CONDITION, REFERENCE, LIST, GROUP_LIST
}
