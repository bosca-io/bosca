# Compiled library localization

Library components use the same `t` markup, `t:<attribute>` bindings, plural forms, and static
`t("key")` calls as application components. Author stable message keys and source text in the
library's BML files. Component import aliases do not change those keys.

```html
<component tag="greeting">
  <prop name="name" type="String"/>
  <p t="design.greeting">Hello { name }</p>
</component>
```

The compiler records each component's messages, plural defaults, placeholder expressions, and
source locations in its binary library contract. Source attachments are useful for navigation;
consuming builds do not read them to discover strings. The library manifest uses format 5.
Rebuild libraries and consumers with matching BML tooling, including wrappers that depend on an
updated library contract.

When a consuming module uses an imported component, its full component closure contributes strings
to `build/generated/bml/resources/bml/i18n-manifest.json`. This includes private helpers and deferred
dependencies. An unused export or unused project-wide import contributes no library strings.
Aliases and shared dependencies contribute each declaration once. Components sharing a BML file
retain their own declarations; file-level script keys are shared by that file's components.

The resulting catalog keeps the existing i18n manifest version and works with the usual
`bosca bml i18n extract`, `status`, and `push` commands. Imported source locations include the
artifact coordinate and original BML path, for example
`example:design:1.0!/bml/greeting.bml`. Translator placeholder examples retain their authored
expressions.

One key identifies one source message across the consuming module. Identical defaults deduplicate;
bare function references inherit an available authored default. Different text, different plural
forms, or plain/plural disagreements fail compilation with both source locations. Give different
messages distinct keys. Change translations through the consuming project's localization catalog.

The consuming server's existing `MessageSource` and locale policy govern every render. Translations
use the host's fallback chain, then the component's authored fallback. Library imports do not add
supported locales, fetch catalogs, or replace host bindings. Browser catalog requests continue using
the same host endpoint. Localized shared pages and catalog responses keep their existing body-based
validators, so translation updates can change responses without rebuilding the library.

Localization declarations participate in compiled contract digests and dependency inputs. A changed
library rebuilds the consuming catalog; wrappers must be rebuilt when an underlying contract changes.
Publication verifies that both component and localization contracts survive archive customization.
A localization conflict removes the old generated catalog, and removing the last declaration removes
the catalog file.
