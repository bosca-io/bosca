# Schema forms

`schema-form` consumes the existing Forms JSON Schema and version-one UI Schema. It does not load
schemas or submit to a backend. The containing application supplies those operations through its
normal server-side GraphQL client and declarative BML form actions.

```html
<form @submit="model.save(form.attributes, ctx)">
  <schema-form id="task-draft" :schema="model.schema" :uiSchema="model.uiSchema"
               :value="model.draft" :options="model.options"
               :controlAliases="model.controlAliases" preserveKey="task-draft" />
  <button type="submit" primary>Save</button>
</form>
```

The native hidden `attributes` field contains a typed JSON object. `name` changes that field name.
`id` distinguishes control identities when several forms share a page. JSON Schema defaults apply
without replacing supplied false, zero or null values. Fields outside the layout remain in the
submitted object. Dot paths address nested properties. AJV validation uses the same draft and
format handling as the Vue Forms package; invalid schemas and unavailable controls block submission
with feedback. Backend authorization and validation remain authoritative.

Supported layout nodes are `section`, `row`, `tabs`, `tab`, `field` and `display`. Rows honor `col`
from one to twelve and stack below 600 pixels. Display controls include alert, divider, heading,
help-text and spacer. Field controls include text-input, textarea, number-input, select, checkbox,
switch, date-picker, tag-input, radio-group, color-picker, json-editor, remote-select, file-upload
and image-upload. Enum and boolean/number
values preserve their JSON types. Shared selects supply local search, tag creation and keyboard
interaction. JSON values use the existing structured editor.

Pass choices by property as `Map<String, List<SchemaFormOption>>`. A domain may map its own stored
control names to neutral controls; for example, WorkOps maps `workops-select` to `select` and supplies
its authorized projects, task types, priorities, statuses and resolutions. The library imports no
WorkOps models or operations. Remote GraphQL/profile/script transport remains an application
responsibility. Unsupported
controls remain visible as errors rather than silently dropping their values.

`remote-select` emits `schemaformchoicesrequest`. Import `SchemaFormChoicesRequest` from
`@bosca/bml-design/schema-form`; its detail contains the stable layout `fieldKey`, `property`,
`query`, `offset`, `signal` and `respondWith`. Call `respondWith` synchronously with a `SelectPage`
or promise. A null query requests initial labels; a string requests a search. Choices use the
existing shared select, preserve a current selection outside the results and support offset pages.
Failed lookups show feedback; retry repeats the failed query and offset. Aborted or unmounted
fields ignore late responses. An adapter that mounts later dispatches `schemaformchoicesready`
on each containing schema form. Applications resolve stored configuration and authorization.

File and image fields reuse `file-field`. Their value is a data-URL string, and Clear publishes
null, matching the existing Forms controls. Images include a preview. Reading reports progress,
can be cancelled and retains the previous value on failure. `accept` is a chooser hint. Schema
forms block submission during a read, and readonly fields retain their values. Remote uploads
remain application-owned operations; these controls read the selected file into the form value.

The bubbling `schemaformchange` detail is `{ property, value, data }`. Applications may use it to
select another stored schema when a domain field changes. A stable `preserveKey` keeps a local draft
through surrounding updates. Use a new key when selecting a different schema, version or option
context. Application actions should retain the submitted draft when rejecting a save. `errors`
associates server feedback with property paths, with the empty key reserved for form-wide feedback.

`readOnly` preserves values while preventing editing. A pending declarative form action makes the
renderer inert until completion. Invalid submissions focus the first field and reveal its tab.
Observers and form listeners close when the component unmounts. The catalog and independent
consumer demonstrate separate application state in simultaneous palettes.
