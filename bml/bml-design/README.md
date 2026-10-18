# Bosca Design Library

Compiled BML controls extracted from Git UI. The library owns presentation; applications own their
documents, forms, state, actions, navigation, and CSS themes. Its Kotlin package is
`bosca.design.generated`, and its artifact coordinate is `io.bosca:bml-design`.

In a workspace application:

```kotlin
dependencies {
    implementation(project(":bml:bml-design"))
}
```

Declare one `src/main/bml/imports.bml`:

```html
<import library="io.bosca:bml-design" />
```

Pages and local components, including nested source directories, can then use:

```html
<card title="Preferences">
  <form @submit="preferences.save(form.displayName)">
    <field label="Display name" hint="Shown beside your activity">
      <input name="displayName" required autocomplete="name" />
    </field>
    <button type="submit" primary icon="save">Save</button>
  </form>
</card>
```

The containing page or component declares the application's state and island as usual. The example
consumer in [examples/design-library](../examples/design-library/README.md) demonstrates this wiring.
Use `<html:button>` and `<html:input>` for native elements after importing their library counterparts.
Local component definitions take precedence throughout their module. An optional `as="design"`
import provides `<design:button>` with the same runtime identity and assets as `<button>`.

## Components

| Export | Props and defaults | Native target and children |
| --- | --- | --- |
| `button` | `size: String = "md"` (`md`, `sm`, `xs`); `loading: Boolean = false`; `type: String = "button"`; `ref: String = ""`; `primary: Boolean = false`; `icon: String = ""`; `disabled: Boolean = false` | Native button receives additional attributes, merged `class`, and declarative click events. Default children supply its accessible name. Loading disables it and overlays an aria-hidden spinner. |
| `input` | `size: String = "md"` (`md`, `sm`); `mono: Boolean = false`; `type: String = "text"`; `ref: String = ""`; `value: String = ""`; `required: Boolean = false` | Native input receives additional attributes and events, including `name`, `disabled`, `readonly`, `autocomplete`, `aria-*`, and `data-*`. No child content. |
| `field` | Required `label: String`; `hint: String = ""`; `hideLabel: Boolean = false` | A wrapping native label contains default children. The hidden-label option retains an accessible label. Only named props and children are consumed. |
| `card` | `title: String = ""`; `padded: Boolean = true` | Native section with an optional heading and a default child body. Only named props and children are consumed. |
| `page-shell` | `fill: Boolean = false`; `narrow: Boolean = false` | Native main with default children, merged classes, and additional attributes. `fill` allocates a flex column; `narrow` uses a centered 400px form surface. |
| `icon` | Required `name: String`; `size: Int = 16`; `strokeWidth: Double = 2.0` | Decorative, aria-hidden Lucide SVG. No child content. |
| `spinner` | `size: String = "sm"` (`xs`, `sm`, `md`) | Aria-hidden spinner, 12/14/20px respectively; stops animation for reduced motion. No child content. |

Regular controls are 32px high, compact controls 30px, and extra-small buttons 24px. Field labels use
an 18px line and a 6px control gap. Buttons support the `ghost`, `danger`, `block`, and `icon-only`
class modifiers. Choose one tone (`primary`, `ghost`, or `danger`); icon-only buttons need an
`aria-label`. Other structural classes and generated scope markers are private styling hooks.
Caller-owned submit forms receive BML's pending marker, which the button's scoped stylesheet uses
to preserve its size and accessible name while displaying progress.

The `page` name remains BML document syntax; the visual layout component is `page-shell`.

Controls forward additional attributes declaratively with BML's compiler-provided `{...attrs}`.
The compiler excludes declared props, combines caller and component classes, and preserves the
component's explicit attribute values. Components do not maintain exclusion lists or handle BML's
internal action-marker props. See [attribute forwarding](../docs/tag-reference.md#forwarding-component-attributes).

## Consumer CSS

Library defaults load before application CSS. Override ordinary custom properties on the root or a
themed container. For example:

```css
.light {
  --bg-1: #fff;
  --bg-3: #e2e8f0;
  --control-bg: #f8fafc;
  --line: #cbd5e1;
  --line-2: #94a3b8;
  --fg-0: #0f172a;
  --fg-1: #1e293b;
  --fg-2: #475569;
  --fg-3: #64748b;
  --accent: #15803d;
  color-scheme: light;
}
```

The initial controls use surface tokens `--bg-1`, `--bg-3`, and `--control-bg`; foreground tokens
`--fg-0` through `--fg-3`; borders `--line` and `--line-2`; `--accent` for focus/primary actions;
`--err` for danger actions; `--control-radius`; and `--font-mono` for monospaced inputs.
`--font-sans` supplies a font stack applications can apply to their document. Defaults do not set
document margins, backgrounds, font sizes, or a color scheme. Component states derive colors where
they are used, so two themed containers can coexist in one document.

## Source ownership

| Library source | Extracted source in Git UI |
| --- | --- |
| `components/button.bml` | `components/ui/button.bml` |
| `components/card.bml` | `components/ui/card.bml` |
| `components/field.bml` | `components/ui/field.bml` |
| `components/input.bml` | `components/ui/input.bml` |
| `components/page-shell.bml` | `components/ui/page.bml` |
| `components/icon.bml` | `components/ui/icon.bml` |
| `components/spinner.bml` | `components/ui/spinner.bml` |
| `client/design.css` | Shared control token defaults from `src/main/client/git-ui.css` |

Component paths are relative to each module's `src/main/bml`; `client/design.css` is under `src/main`.
Lucide glyphs retain their [ISC notice](src/main/resources/META-INF/licenses/bml-design/lucide.txt),
packaged under `META-INF/licenses/bml-design/`. The library has no Git dependency.

Build and validate the binary and original source archive from the workspace root:

```sh
./gradlew :bml:bml-design:bmlValidateLibrary :bml:examples:design-library:test
```
