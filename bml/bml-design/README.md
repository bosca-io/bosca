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
Use `<html:button>`, `<html:input>` and `<html:section>` for native elements after importing their library counterparts.
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
| `checkbox` | Required `label: String`; `description: String = ""`; `checked: Boolean = false` | Wrapping label with a native 14px checkbox. Additional attributes and classes go to the input, including `name`, `value`, `required` and `disabled`. Change events bubble to the label root. No child content. |
| `switch` | Required `label: String`; `description: String = ""`; `checked: Boolean = false`; `disabled: Boolean = false` | Wrapping label with a native checkbox and `role="switch"`, presented as a 32×18px track. Additional attributes/classes go to the input. Change events bubble to the label root. No child content. |
| `alert` | `ref: String = ""`; `hidden: Boolean = false`; `tone: String = "error"` (`error`, `success`); `compact: Boolean = false` | Native paragraph with `role="alert"`, forwarded attributes/classes and default children. Compact text is 12px with an 18px line. |
| `badge` | `tone: String = "neutral"` (`neutral`, `purple`, `info`, `success`, `warning`, `error`, `accent`); `color: String = ""` | Native span with forwarded attributes/classes and default children. A supplied CSS color overrides the tone. |
| `empty-state` | `message: String = ""`; `title: String = ""`; `icon: String = "folder"` | Native div with forwarded attributes/classes, decorative icon, optional title/message and default action children. |
| `loading-state` | `label: String = "Loading…"`; `compact: Boolean = false`; `ref: String = ""` | Native div with `role="status"`, forwarded attributes/classes, decorative spinner and visible label. No child content. |
| `table-scroll` | `label: String = "Scrollable table"` | Focusable, labelled region with forwarded attributes/classes and default table children. Horizontal overflow stays inside the region; keyboard focus uses the accent color. |
| `bosca-mark` | No named props | The actual 22×22px Bosca SVG, labelled `Bosca`, with forwarded attributes/classes. `aria-hidden` can mark it decorative. No child content. |
| `app-header` | Required `brand: String` and `href: String`; `product: String = ""` | Header with a brand link/mark and default navigation children. Extra attributes/classes target the header. A 224px sidebar becomes a wrapping header at 700px. Scoped client code reveals the active item in overflowing navigation on mount, resize and updates within the header. |
| `panel` | No named props | Native section with a surface, border, 20px padding and default children. Additional attributes/classes target the section. |
| `section` | No named props | Native section with 16px vertical spacing, a bottom border and default children. Additional attributes/classes target the section. |
| `page-heading` | Required `title: String`; `subtitle: String = ""`; `trail: List<NavigationItem> = emptyList()`; `crumb: String = ""`; `icon: String = ""`; `iconColor: String = "var(--accent)"`; `badge: String = ""`; `badgeTone: String = "neutral"`; `badgeColor: String = ""` | Header with breadcrumbs, title/icon, optional badge/subtitle and default action children. Additional attributes/classes target the header. The current crumb defaults to the title. |
| `section-heading` | Required `title: String`; `subtitle: String = ""` | Header with a title, subtitle and default action children. Additional attributes/classes target the header. |
| `disclosure-heading` | `framed: Boolean = false` | Native summary inside caller-owned `details`, with default label children and a decorative chevron that follows the open state. Additional attributes/classes target the summary. |
| `icon-button` | Required `icon: String` and `label: String`; `ref: String = ""`; `type: String = "button"`; `disabled: Boolean = false`; `size: String = "sm"` (`sm`, `xs`) | Native button with a decorative icon, required accessible name and tooltip. Additional attributes/classes and actions reach the button. Default dimensions are 30×30px; `xs` is 24×24px. No child content. |
| `icon-link` | Required `icon: String`, `label: String` and `href: String` | Native 30×30px anchor with a decorative icon, accessible name and tooltip. Additional attributes/classes target the anchor, including `target`, `rel` and `download`. Supports the `primary` class modifier. No child content. |
| `tooltip` | Required `content: String`; `placement: String = "top"` (`top`, `bottom`, `left`, `right`); `delay: Int = 200` (milliseconds) | Inline span with default children. The first descendant button/link/tabbable element is its trigger, falling back to the root. Additional attributes/classes target the span. |

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

Checkbox and switch state belongs to the caller. Use `checked` with a declarative `@change` action
when server state controls the value; use native `name`/`value` attributes to include checked controls
in form submission. Disabled and unchecked controls follow native form rules. The compiled
consumer's `/foundation` page demonstrates repeated instances, native form values and action patches.
In BML action arguments, `form.checkboxName` resolves a checkbox's checked state to a Boolean.

Breadcrumb links use `bosca.design.model.NavigationItem(label, href, selected)`. Applications own
destinations, selected-state calculation and every trail entry, including any product/root label.
An empty `href` renders plain trail text. The final current crumb comes from `crumb` or `title`;
there is no fixed product prefix. Git's existing presentation models use this same library type.

Tooltips open on pointer hover or keyboard focus and dismiss on Escape, activation or departure.
Their text remains available while hovered. Placement can flip or clamp at viewport edges.
Each visible tooltip adds its own ID to `aria-describedby`, preserving existing descriptions.
The helper follows a replaced trigger and removes timers, listeners and tooltip nodes when its
component unmounts. Clicking tooltip text does not activate its parent button or anchor.
Icon actions retain native `title` labels when scripts are disabled.

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

Feedback uses `--ok`, `--warn`, `--info`, `--err` and `--purple`; alerts can override `--error-fg`
within a theme and otherwise use `--err`. Table cells use the same foreground/border tokens.
The library's table stylesheet targets only its scrolling wrapper, including caller-owned table
children; unrelated tables keep their own styles.

Layout adds `--r-md` for panel radius, `--fg-4` for breadcrumb separators and `--on-accent` for the
page-heading icon's foreground. The mark uses `--mark-top`, `--mark-right` and `--mark-left`.
Applications own the document's flex/grid arrangement and navigation-link styling; the `/layout`
consumer page demonstrates a sidebar, responsive header and CSS-customized mark/panel colors.

Tooltip nodes stay inside their owning component and use native manual popovers to escape clipping.
CSS custom properties inherit normally, including live theme-class changes. The library-owned
`.bosca-tooltip` surface uses `--bg-3`, `--fg-0` and `--line-2`; optional `--tooltip-bg`, `--tooltip-fg`,
`--tooltip-border` and `--tooltip-shadow` overrides provide specific tooltip styling. Default shadow
is `0 4px 12px #0005`. This surface rule lives in the real library stylesheet because the node is
created at runtime. Consumers do not copy colors into JavaScript or configure a theme service.

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
| `components/checkbox.bml` | `components/ui/checkbox.bml` |
| `components/switch.bml` | `components/ui/switch.bml` |
| `components/alert.bml` | `components/ui/alert.bml` |
| `components/badge.bml` | `components/ui/badge.bml` |
| `components/empty-state.bml` | `components/ui/empty-state.bml` |
| `components/loading-state.bml` | `components/ui/loading-state.bml` |
| `components/table-scroll.bml` | `components/ui/table-scroll.bml` |
| `components/bosca-mark.bml` | `components/ui/bosca-mark.bml` |
| `components/app-header.bml` | `components/ui/app-header.bml` |
| `components/panel.bml` | `components/ui/panel.bml` |
| `components/section.bml` | `components/ui/section.bml` |
| `components/page-heading.bml` | `components/ui/page-heading.bml` |
| `components/section-heading.bml` | `components/ui/section-heading.bml` |
| `components/disclosure-heading.bml` | `components/ui/disclosure-heading.bml` |
| `components/icon-button.bml` | `components/ui/icon-button.bml` |
| `components/icon-link.bml` | `components/ui/icon-link.bml` |
| `components/tooltip.bml` | `components/ui/tooltip.bml` |
| `client/helpers/tooltip.ts` | `src/main/client/tooltip.ts` |
| `client/design.css` | Shared control token defaults from `src/main/client/git-ui.css` |

`src/main/kotlin/bosca/design/model/NavigationItem.kt` owns the shared navigation model formerly
defined in Git UI. The original Git components remain until the application's component cutover.

Component paths are relative to each module's `src/main/bml`; `client/design.css` is under `src/main`.
Lucide glyphs retain their [ISC notice](src/main/resources/META-INF/licenses/bml-design/lucide.txt),
packaged under `META-INF/licenses/bml-design/`. The library has no Git dependency.

Build and validate the binary and original source archive from the workspace root:

```sh
./gradlew :bml:bml-design:bmlValidateLibrary :bml:examples:design-library:test
```
