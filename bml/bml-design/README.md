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
| `dialog` | Required `title: String` and `triggerLabel: String`; `subtitle: String = ""`; `size: String = "md"` (`sm`, `md`, `lg`); `triggerIcon: String = ""`; `icon: String = ""`; `primary: Boolean = false`; `triggerIconOnly: Boolean = false`; `triggerDisabled: Boolean = false`; `showTrigger: Boolean = true`; `triggerStyle: String = "button"` (`button`, `block`, `plain`, `link`, `ghost`); `returnFocus: String = ""`; `forceOpen: Boolean = false`; `openRequest: String = ""`; `completed: Boolean = false` | Wrapper div receives additional attributes/classes and contains a native modal dialog. Default children supply the body/form/footer. `ref="cancel"` or `data-dialog-close` on a projected control closes it. Native panel widths are 400/520/760px, constrained to the viewport. |
| `confirm-dialog` | Required `title: String` and `description: String`; `triggerLabel: String = "Remove"`; `icon: String = "trash"`; `forceOpen: Boolean = false`; `triggerIconOnly: Boolean = true`; `triggerStyle: String = "button"` | Small dialog with description and default action children. Additional attributes/classes reach the dialog wrapper. The application owns confirmation actions and their outcomes. |
| `popover` | Required `label: String`; `icon: String = ""`; `iconOnly: Boolean = false`; `width: Int = 360` | Native details with a labelled summary and default children in a labelled region. Additional attributes/classes target details. Scripts enhance its content into a viewport-fitted manual popover; without scripts it remains a native disclosure. |
| `copy` | Required `value: String`; `label: String = "Copy"`; `showStatus: Boolean = true` | Root div receives additional attributes/classes. Its 30×30px native button copies the value, switches decorative icons and reports status. No child content. Visible status reserves 26ch so feedback does not move neighboring content; `showStatus="false"` keeps status accessible with no visible message space. |
| `copy-field` | Required `value: String` and `label: String`; `copyLabel: String = "Copy value"` | Root div receives additional attributes/classes. A labelled readonly input and compact copy action display a single-line value. No child content. Focus selects the value; denied/unavailable clipboard access selects it and exposes manual-copy guidance. |
| `toast` | Required `id: String`; `message: String = ""`; `sequence: Int = 0`; `tone: String = "info"` (`info`, `success`, `warning`, `error`); `duration: Int = 4000` (milliseconds) | Manual-popover root receives additional attributes/classes. `id` supplies its stable `data-bml-preserve` key, rather than a native DOM ID. No child content. The caller issues each nonempty notice with a new positive sequence; ordinary updates and same/older sequences do not replay it. |
| `search-input` | `value: String = ""`; `name: String = "q"`; `placeholder: String = "Search…"`; `label: String = "Search"`; `preserveKey: String = ""`; `disabled: Boolean = false`; `autofocus: Boolean = true` | Search wrapper with decorative icon, clear action and default trailing action children. Additional attributes/classes/events target the native search input. The clear action emits one bubbling `input` event and returns focus to the query. |
| `segmented-control` | Required `label: String`, `name: String`, `options: List<String>` and `value: String`; `values: List<String> = emptyList()`; `icons: List<String> = emptyList()`; `preserveKey: String = ""` | Radiogroup receives additional attributes/classes/events. A hidden native input supplies the selected form value. Buttons use indexed `values` or fall back to option labels; optional indexed icons are decorative. Emits bubbling `segmentchange` after updating its native value. No child content. |
| `view-tabs` | Required `label: String`, `tabs: List<String>` and `selected: String` | Tablist receives additional attributes/classes/events. Emits bubbling `viewchange` with the selected label as its string detail. The application owns panels, IDs, accessible relationships and visibility. No child content. |
| `tabs` | Required `label: String` and `items: List<NavigationItem>`; `header: Boolean = false` | Native nav receives additional attributes/classes and default trailing action children. Destinations remain native anchors with `aria-current="page"` on the current item. The scoped helper fits links around the current destination and a `More` overflow disclosure. |
| `select` | `name: String = ""`; `ref: String = "native"`; `placeholder: String = "Select…"`; `label: String = "Selection"`; `icon: String = ""`; `searchable`, `async`, `multiple`, `allowCustom`, `disabled`, `loading`, `required`: `Boolean = false`; `size: String = "md"`; `maxTags: Int = 3`; `minQuery: Int = 0`; `debounce: Int = 250` (milliseconds); `preserveKey: String = ""` | Additional attributes/classes target the native select. Default children are caller-owned options/optgroups. The helper enhances it with a labelled combobox, listbox, optional search/pagination and selected tags; native values remain the form submission contract. |
| `tree` | Required `label: String`; `heading: String = ""`; `local: Boolean = false`; `embedded: Boolean = false`; `preserveKey: String = ""` | Section receives additional attributes/classes and contains a labelled tree with default item children. `local` manages disclosure of already-rendered rows; otherwise caller actions own folder loading. |
| `tree-item` | Required `path: String` and `label: String`; `href: String = ""`; `directory`, `expanded`, `selected`, `wrap`: `Boolean = false`; `depth: Int = 0` | Flat ARIA tree item. Additional attributes/classes/actions target its native directory button or native file anchor. Paths identify hierarchy, depth controls indentation/ARIA level, and selected file links use `aria-current="page"`. No child content. |
| `time` | Required `value: String`; `relative`, `ago`, `inheritTypography`: `Boolean = false`; `format: String = ""` | Native time receives additional attributes/classes, its original value as datetime/title and an English server-rendered label. No child content. Use `html:time` for a native timestamp after importing this component. |
| `comment-editor` | Required `id: String` and `label: String`; `value`, `error`, `hint`, `describedBy`: `String = ""`; `submitDisabled: Boolean = false`; `submitLabel: String = "comment"`; `placeholder: String = "Write a comment…"`; `name: String = "body"` | Native required textarea receives additional attributes/classes, its ID and preservation key. Default children supply action controls. The caller owns its surrounding form, submission and error outcomes. |

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

Dialog triggers open a native modal, focus the first available field, and contain Tab navigation.
Escape, backdrop clicks, the close control and projected cancel controls dismiss it. Focus returns
to the trigger, or to `returnFocus` within the nearest island when supplied. A rising `forceOpen`
or a new nonempty `openRequest` opens it; a rising `completed` closes it once. A request remains
consumer state: issue a new request to reopen after dismissal. Forms and failed-submit drafts belong
to the application. The `/overlays` fixture shows an error response retaining submitted values and
reopening through `forceOpen`, plus a preserved dialog whose server-owned request/completion
attributes update while its client-owned draft DOM stays intact through `data-bml-preserve`.
Use `<html:dialog>` when a native element is needed after importing the component.
`ghost` selects the extra-small icon trigger when `triggerIconOnly` is true; `block` and `plain`
control the text trigger presentation.

Popovers preserve their trigger/content relationship through unique IDs and `aria-expanded`.
Opening focuses the first available input. Escape and a projected `ref="cancel"` return focus to
the summary; outside pointer presses dismiss without moving focus. Menu children with `role="menu"`
and enabled `role="menuitem"` entries support arrows and Home/End. Resize, scrolling, content-size
changes and nested island updates reposition the panel. Listeners and ResizeObservers are removed
when the component unmounts. A nested popover consumes Escape before its containing modal.

Copy feedback changes the accessible button label to `Copied`, announces `Copied.` and resets after
two seconds. A subsequent click restarts the timeout. A bubbling `copyresult` event carries
`{ copied: boolean }`; applications can observe it without supplying a transport. Denied or unavailable
clipboard access reports failure and leaves the value available for manual copying. The copy field
focuses/selects its native input and hides the guidance after a successful retry. Inputs remain readonly
and manually selectable with scripts disabled; icon buttons retain native title labels.

`copy` supports arbitrary strings, including newlines; `copy-field` displays a native single-line input.
The helper follows changed value/label props, including updates to a preserved component root.
Older overlapping results and results after unmount cannot overwrite current feedback or emit a stale
event. Timers and tooltip listeners are removed on disposal. Visible status reserves space; quiet status
preserves an icon-only footprint. Icon visibility uses local hidden-state rules and needs no document reset.

Toast state belongs to the application. Give each toast a stable key within its updating region,
keep its sequence monotonic, and increment it for every notice, including repeated text. A new
sequence restarts its timeout; unrelated patches retain the active notice and remaining time.
Success/info/warning use a polite status region; errors use an assertive alert. Notifications do
not take focus from the caller or move page content. Dismissal returns focus when the viewer had
focused the notification, using the remembered target or an available control in the owning island.

Hover, keyboard focus and document visibility pause expiry. Focus and pointer interest survive a
preserved root's temporary removal from the native top layer. Escape, the dismiss button or expiry
close it. Long text scrolls with a keyboard focus indicator while the close action stays visible.
The surface fits the viewport, including short screens; reduced motion removes the entrance animation.
Unmount removes timers, listeners and ResizeObservers. Each instance owns one notice; queues and
progress notifications are application concerns. Live notification behavior requires client scripts.


Search input retains the source's autofocus default; pass `:autofocus="false"` for embedded or repeated
controls. `disabled` applies to its input and clear action; readonly inputs cannot be cleared.
The empty clear action is hidden without a document reset. Native query fields and initial segmented
form values remain available with scripts disabled; clear, segment choices and local view switching
need client scripts. Application controllers own debounce, search requests, URL state and failures.

Segmented controls and local tabs use roving Tab entry, Left/Right wrapping and Home/End. If the
selected value is absent, the first option remains keyboard reachable without inventing a selection.
Empty groups contain no focus target. Wide groups scroll within their available width.

Use a stable `preserveKey` on search/segments, or `data-bml-preserve` on view tabs, to retain the
query draft, selection/caret, focused option and DOM through unrelated island updates. A changed
server `value`/`selected` updates retained state; unchanged server values leave local drafts alone.
Preserved search also reconciles its name, label, placeholder and disabled state. Preserved group
children remain client-owned: remove/change the preservation key when changing the option/tab list,
icons or projected actions. Without preservation, the next server render supplies the full child DOM.
Every helper uses component lifecycle listeners and removes them on unmount.

The `/selection` consumer uses declarative form and segment actions, a scoped caller handler for
`viewchange`, and caller-owned labelled panels with unique `aria-controls`/`aria-labelledby` links.
It demonstrates rejected search drafts, controlled changes, independent instances and consumer CSS.

Route tabs use the same `NavigationItem` model as breadcrumbs. Applications supply every label,
destination and current-page flag; the library does not intercept link navigation or own URL state.
`header` retains the full-width page-heading spacing (22px gutters, 16px below 600px).
Projected actions sit beside the link row and receive their usual application action context.

With scripts, the current destination remains in the row; an unusually long current label truncates
visually while preserving its full link name. Overflow destinations are native anchors in a labelled
manual popover under the owning nav. The popup inherits consumer CSS, escapes clipping, clamps to
the viewport, scrolls on short screens and follows resize/scroll. `More` exposes unique
`aria-controls` and `aria-expanded` values. Enter/click opens it; Up/Down, Home/End navigate its
links, Escape returns focus, and Tab can leave it. Outside pointer/focus and link activation dismiss.
If a resize makes the focused destination fit in the row, focus follows that same destination.
Without scripts, every destination remains in a horizontally scrolling native link row.

An unchanged tab list can use `data-bml-preserve` to retain the open menu and focused link through
unrelated updates, including updates to the nav's accessible label. Change/remove the preservation
key when changing `items` or projected children, since those children remain client-owned.
Unpreserved lists render their new links normally. Component lifecycle cleanup removes event
listeners and ResizeObservers and closes the native popover on unmount.

Select children supply native option values, labels, selected/disabled state and optgroups.
`<html:select>` keeps a plain native select available after importing the library. With scripts
disabled, the component remains a styled native select, including multiple values and required
validation. With scripts, it keeps that native select for form submission and exposes a combobox.
Additional native `aria-describedby`/`aria-labelledby` values reach the enhanced trigger. A wrapping
`field` label supplies its visible name; provide a matching `label` for the native fallback.

Keyboard arrows, Home/End, Enter and non-searchable Space choose enabled options. Escape returns
focus and Tab leaves the popup, with access to page buttons when present. Native required validation
focuses the trigger and marks it invalid. Selection emits one bubbling native `change` event.
Multiple choices retain repeated native form values, show up to `maxTags` removable tags and a
remaining count, and support Backspace removal when the query is empty. Disabled optgroups remain
disabled. `loading` disables enhanced interaction while retaining native submission values.
Native form resets refresh the displayed selection, including after a retained-root action patch.

Local searchable choices filter their native labels/values. `allowCustom` adds a `Use “…”` choice
and stores its actual value as the native option and displayed selection. Async controls emit
`selectsearch` with `{ query, offset, resolve, reject }` after `debounce`; zero disables the delay.
The caller supplies the search transport through its existing BML server/proxy abstractions.
`resolve` accepts an option array or `{ options, previousOffset?, nextOffset? }`; options have
`value`, `label`, optional `disabled` and optional `custom`. Page offsets are supplied by the caller.
The helper retains selected native options and remembered labels while other pages are shown.
`minQuery` suppresses requests below its threshold. Busy, empty and rejected requests have visible
status feedback; newer queries, close/reopen and disposal prevent stale results from changing it.

Mark caller-owned `<option data-persistent>` entries to keep them available below the query
threshold and across async pages, busy states and failures. Their explicit labels/values win over
matching returned values. The string `all` has no special meaning. Persistent choices can also be
used with local filtering. The library does not own an `All …` convention or application filters.

`preserveKey` retains the whole enhanced control, local native selection, query/caret/focus and open
popup through unrelated island updates. Disabled/loading/required/name/label/placeholder settings
reconcile through its server-owned root attributes. Change/remove the key when changing options,
multiple mode, icons or additional native attributes; those child nodes remain client-owned.
The popup stays under its owning root, inherits ordinary CSS, escapes clipping and fits narrow/short
viewports. Unmount closes it, cancels debounce work, ignores pending results and removes listeners
and ResizeObservers. Each instance owns its options and requests.

Import the producer-owned event types through their stable type-only module:

```ts
import type { SelectOption, SelectPage, SelectSearch } from "@bosca/bml-design/select";

ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", event => {
  const { query, offset, resolve, reject } = event.detail;
  // Call the application's search transport, then resolve options or a SelectPage.
});
```

The compiled library JAR contains `client/contracts/select.d.ts`; the existing library linker
verifies and extracts it when linking the selected components. Run `bmlPrepareClient` and include
`build/generated/bml/ts/**/*.ts` in the application's TypeScript configuration. This includes the
declarations without a paths alias to the producer, a sources JAR or a separate npm package.
Type-only imports add no browser code. The independent consumer's adapter uses this import with
BML's same-origin contract client. The library's own helper uses the same declaration.

## Trees, timestamps and comments

Use `tree-item` as flat children of `tree`, with unique, stable slash-separated paths and explicit
depths. File destinations remain native links; directory controls remain separate native buttons.
Down/Up, Home/End, Right to expand/enter, Left to collapse/return and type-ahead operate on visible
items with one roving tab stop. Repeating a letter cycles matching labels. Enter follows a file link
or activates its directory button. The helper does not load data, persist expansion or change routes.

`local` trees disclose descendants already in the child list and honor initial `expanded` props.
Use a stable `preserveKey` to retain the whole local tree, disclosure state and focus through unrelated
updates. Its children remain client-owned; change/remove that key when replacing the item list or
its props. For server-loaded trees, the application supplies directory actions, expanded state and
new rows. Preserve the native directory control with an additional `data-bml-preserve` attribute so
Right Arrow can enter an asynchronously added child after the update. The helper follows the
currently focused control and does not take focus back from another element. Nonnegative depth
sets the ARIA level; indentation is capped at twelve levels. `wrap` makes long labels wrap.
Without scripts, the rendered file links remain available.

`time` parses offset timestamps. Its default label converts to UTC and displays `MMM d, HH:mm UTC`.
A custom Java date/time pattern takes priority and retains the supplied offset. Relative labels use
`just now`, minutes, hours and days for ages below a week, then `MMM d`; future labels use `in` and
`ago` optionally adds the past suffix. Labels use English and are computed at server render time,
with no browser refresh timer. Invalid timestamp text remains escaped literal text; invalid format
patterns raise their normal error. `inheritTypography` inherits the surrounding font and color.

Give each `comment-editor` a unique `id` and place it in a caller-owned form, projecting its action
buttons into the default slot. The default form field name is `body`. The helper grows the textarea
from 78px to 320px, blocks blank submissions, and supports Ctrl+Enter or Command+Enter. Native
`readonly` and `disabled` attrs are respected. `submitDisabled` blocks submission while retaining an
editable draft; a custom `hint` can explain that state. The default shortcut hint follows the platform
and hides on touch-only devices. `describedBy` adds application description IDs to the current
hint/error relationships. Form pending state makes the field readonly, blocks actions and temporarily
hides the prior error. Completion restores the normal state; `error` supplies the caller's alert.

The textarea's preservation key retains its native value, focus and caret when its form is replaced.
An unchanged server `value` retains a draft; a changed value replaces it and becomes its native reset
default. Change `id` to reset after successful submission, including when the server value was already
empty. The helper reconnects to the current form, and unmount removes its listeners and observer.
It supplies no storage, transport, permissions or rich-text formatting.

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

Search and segmented controls add `--bg-0` for their base surface. The initial controls use surface tokens `--bg-1`, `--bg-3`, and `--control-bg`; foreground tokens
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

Dialogs and popovers retain their owning DOM subtree in the top layer, so they use consumer colors
without a copied palette. Their surfaces use `--bg-1`, `--fg-1`, `--line-2` and the shared accent;
modal footers use `--bg-2`. `--r-sm` and `--r-lg` default to 9px and 16px. Optional `--dialog-shadow`,
`--dialog-backdrop` and `--popover-shadow` override the default shadows and backdrop color.

Projected modal forms use the documented `modal-form`, `modal-fields` and `modal-footer` classes.
The library stylesheet scopes these rules to its native dialog panel, allowing the body to scroll
independently of the footer. These classes do not style unrelated forms elsewhere in the document.

Toasts use `--bg-1` and `--fg-1` for the solid surface/text, `--info`, `--ok`, `--warn` or `--err`
for the tone and derived border, `--r-md` for radius, and `--accent` for keyboard focus. The optional
`--toast-shadow` overrides the default shadow. `--toast-message-max-height` defaults to `240px`
and is constrained further by the viewport. `--toast-left` and `--toast-top` default to `50%` and
`75dvh`; consumer CSS can position separate themed instances, as in `/notifications`. Short screens
use a 16px bottom inset, and the helper shifts a tall surface upward to retain viewport margins.
Color changes on an already-open notification follow ordinary CSS inheritance.

Route tabs use `--fg-3`, `--fg-1`, `--fg-0`, `--line` and `--accent` for normal/current/focused links.
Their overflow surface inherits `--bg-2`, `--fg-1`, `--bg-3` and `--line-2`;
`--tabs-menu-shadow` overrides the default `0 14px 40px #0006` shadow.

Select surfaces use `--control-bg`, `--bg-2`, `--bg-3`, `--fg-0` through `--fg-4`, `--line`,
`--line-2`, `--accent`, `--err` and `--r-sm`; `--select-shadow` overrides the popup shadow.
Dynamic option/tag rules live in the real library stylesheet under its owning select marker.

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
| `components/dialog.bml` | `components/ui/dialog.bml` |
| `components/confirm-dialog.bml` | `components/ui/confirm-dialog.bml` |
| `components/popover.bml` | `components/ui/popover.bml` |
| `components/copy.bml` | `components/ui/copy.bml` |
| `components/copy-field.bml` | `components/ui/copy-field.bml` |
| `components/toast.bml` | `components/ui/toast.bml` |
| `components/search-input.bml` | `components/ui/search-input.bml` |
| `components/segmented-control.bml` | `components/ui/segmented-control.bml` |
| `components/view-tabs.bml` | `components/ui/view-tabs.bml` |
| `components/tabs.bml` | `components/ui/tabs.bml` |
| `components/select.bml` | `components/ui/select.bml` |
| `components/tree.bml` | `components/ui/tree.bml` |
| `components/tree-item.bml` | `components/ui/tree-item.bml` |
| `components/time.bml` | `components/ui/time.bml` |
| `components/comment-editor.bml` | `components/ui/comment-editor.bml` |
| `client/helpers/tree.ts` | `src/main/client/tree.ts` |
| `client/helpers/comment-editor.ts` | `src/main/client/comment-editor.ts` |
| `client/helpers/select.ts` | `src/main/client/select.ts` |
| `client/contracts/select.d.ts` | Public event interfaces from `src/main/client/select.ts` |
| `client/helpers/tabs.ts` | `src/main/client/tabs.ts` |
| `client/helpers/search-input.ts` | Scoped client script in `components/ui/search-input.bml` |
| `client/helpers/segmented-control.ts` | Scoped client script in `components/ui/segmented-control.bml` |
| `client/helpers/view-tabs.ts` | Scoped client script in `components/ui/view-tabs.bml` |
| `client/helpers/toast.ts` | `src/main/client/toast.ts` |
| `client/helpers/copy.ts` | `src/main/client/copy.ts` |
| `client/helpers/dialog.ts` | `src/main/client/dialog.ts` |
| `client/helpers/popover.ts` | `src/main/client/popover.ts` and its component's disclosure behavior from `src/main/client/disclosure.ts` |
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
