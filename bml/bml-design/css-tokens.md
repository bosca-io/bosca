# Consumer CSS tokens

Import the library once with `<import library="io.bosca:bml-design" />`. Its defaults load before
application CSS and use `:where(:root)`. Set variables on `:root`, a class, or a `[data-theme]`
subtree. The library selects no theme attribute and sets no document background, layout, reset or
`color-scheme`. Applications apply their own document typography and choose `color-scheme: light`
or `dark` where native controls need it.

```css
.product-theme {
  --bg-0: #f0f9ff;
  --bg-1: #fff;
  --bg-2: #e0f2fe;
  --bg-3: #bae6fd;
  --fg-0: #082f49;
  --fg-1: #0c4a6e;
  --fg-2: #075985;
  --fg-3: #0369a1;
  --fg-4: #0284c7;
  --line: #7dd3fc;
  --line-2: #38bdf8;
  --control-border: #0369a1;
  --accent: #0369a1;
  --ok: #15803d;
  --warn: #a16207;
  --err: #b91c1c;
  --info: #1d4ed8;
  --purple: #7c3aed;
  --shadow-color: #164e63;
  --backdrop-color: #164e63;
  color: var(--fg-0);
  color-scheme: light;
  font-family: var(--font-sans);
}
```

Two differently themed roots can coexist. Component states derive values at their point of use;
changing a parent class updates mounted controls and open overlays. Optional control, overlay and
editor overrides below are intentionally unset by default. In particular, `--control-bg` falls back
to the local `--bg-2`, `--control-border` to each control's existing local `--line-2` or `--line`,
and `--control-radius` to the local `--r-sm`. Defining those aliases at the
document root would resolve them there and hide a subtree's later base-token changes. Set an alias
explicitly when that separate appearance is intended.

## Palette defaults

| Token | Default | Role |
| --- | --- | --- |
| `--accent` | `#64748b` | Primary borders/icons, focus and selected states |
| `--on-accent` | `#fff` | Foreground on filled heading icons, switch thumbs and editor menus |
| `--bg-0` | `#0a0c12` | Base search/segmented surface |
| `--bg-1` | `#10131c` | Cards, panels, dialogs and popovers |
| `--bg-2` | `#161a25` | Controls, secondary panels and active editor lines |
| `--bg-3` | `#1d2230` | Hover/raised surfaces, selected options and tooltip background |
| `--fg-0` | `#f3f5fb` | Strong text and active labels |
| `--fg-1` | `#cdd2e2` | Ordinary component text |
| `--fg-2` | `#969cb1` | Secondary text and icons |
| `--fg-3` | `#6c7388` | Hints, placeholders and muted text |
| `--fg-4` | `#454b60` | Separators and quiet control adornments |
| `--line` | `#232839` | Ordinary borders and dividers |
| `--line-2` | `#2e3447` | Control fallback and overlay borders |
| `--ok`, `--warn` | `#34d99a`, `#ffb547` | Success and warning |
| `--err`, `--info` | `#ff5470`, `#5ec5ff` | Error/danger and information |
| `--purple` | `#a78bff` | Purple badge tone and source keywords |
| `--mark-top`, `--mark-right`, `--mark-left` | `#5ec5ff`, `#79d3ff`, `#409dcc` | Three faces of the original Bosca mark |
| `--shadow-color`, `--backdrop-color` | `#000`, `#000` | Neutral elevation and modal backdrop colors |

Git and Studio retain `--brand-1`, `--brand-2`, `--brand-accent`, `--brand-grad` and
`--brand-grad-soft` for application branding and ambient surfaces. Those gradients belong to the
application. Exported controls use `--accent`; the mark uses its three face colors. This retains
Git's separate accent and brand choices without imposing its document artwork on another consumer.

Status borders, badge fills, selected options and hover states mix the local palette with transparency
or another local surface. Disabled controls retain those colors with reduced opacity; pending buttons
retain their size and replace visible content with a spinner. There is no global text-selection rule:
source and JSON editors expose selection hooks below, and applications own ordinary document selection.

## Controls, typography and focus

| Token | Default | Applies to |
| --- | --- | --- |
| `--control-bg` | Local `--bg-2` fallback | Buttons, inputs, native selects, popover triggers and source search controls |
| `--control-border` | Existing local `--line-2` or `--line` fallback | Neutral buttons/icon links, inputs/textareas, selects, search controls, switch tracks, segmented frames, popover triggers and source-search fields/buttons; surface frames and dividers keep their palette tokens |
| `--control-radius` | Local `--r-sm` fallback | Buttons and inputs |
| `--control-height-md`, `--control-height-sm`, `--control-height-xs` | `32px`, `30px`, `24px` | Button/input/select sizes, icon actions and popover triggers; picker query rows use the small height |
| `--control-gap` | `6px` | Button icon/content spacing and popover trigger spacing |
| `--focus-width` | `2px` | Two-pixel keyboard focus outlines and JSON edit outlines |
| `--focus-offset` | `3px` | Outward focus rings on buttons, links, checkboxes, switches and popover triggers |
| `--r-sm`, `--r-md`, `--r-lg` | `9px`, `12px`, `16px` | Compact surfaces, cards/panels/editors, modal corners |
| `--font-sans` | `"Geist", "Inter", -apple-system, system-ui, sans-serif` | Inherited control typography and overlay/editor text |
| `--font-mono` | `"Geist Mono", "JetBrains Mono", ui-monospace, monospace` | Monospaced inputs, source and JSON text |

Set `--control-border` separately when controls need clearer boundaries than surrounding dividers.
The catalog keeps subtle surface borders and sets this override in each palette. Focus, expanded,
primary, danger, ghost and disabled states retain their existing styling.

Focus color follows `--accent`, or `--json-accent` inside the structured editor. Tab rows, trees,
scrolling regions and popup options retain their existing inset offsets to keep outlines visible
inside clipped surfaces. One-pixel editor focus borders remain one pixel. Native checkbox/switch
dimensions, text sizes and layout spacing are independent of the shared control heights.

Use documented component props and stable modifiers for other changes: button `size`, `primary`,
`danger`, `ghost`, `block` and `icon-only`; input `size`/`mono`; page-shell `fill`/`narrow`; dialog
`size`/`triggerStyle`; source-view `compact`/`bare`; select `size` and its native option content.
Forward an application class through the documented attribute target when styling that control.
Generated BML scope IDs and third-party editor classes are not consumer styling hooks.

## Optional component overrides

| Tokens | Default when unset |
| --- | --- |
| `--error-fg` | `--err` for alert text |
| `--tooltip-bg`, `--tooltip-fg`, `--tooltip-border` | `--bg-3`, `--fg-0`, `--line-2` |
| `--tooltip-shadow` | `0 4px 12px` with shadow color at one-third opacity |
| `--dialog-shadow`, `--dialog-backdrop` | `0 30px 100px` with shadow color at 8/15 opacity; backdrop color at 60% |
| `--popover-shadow`, `--select-shadow`, `--tabs-menu-shadow` | `0 12px 30px`, `0 12px 40px`, `0 14px 40px`, all at 40% shadow opacity |
| `--toast-shadow` | `0 12px 40px -10px` with shadow color at 45% |
| `--select-active-border` | `--accent` for an expanded picker |
| `--toast-left`, `--toast-top`, `--toast-message-max-height` | `50%`, `75dvh`, `240px`, further constrained to the viewport |

The complete shadow/backdrop overrides accept ordinary CSS values, including `none` for shadows.
Changing `--shadow-color` or `--backdrop-color` updates the defaults at the owning subtree without
redeclaring each overlay. Tooltip, select and tab popups stay in that subtree and use the native top
layer; the library does not copy theme colors to JavaScript or move them to the document body.

Source colors use the [documented `--source-*` hooks](README.md#read-only-source), with palette-derived
defaults for surface, foreground, gutter, selection, active lines, search and syntax roles.
`--source-search-fg` supplies text over both search-match backgrounds, independently of syntax colors.
Pair it with `--source-search-match` and `--source-selection` when overriding those backgrounds. Structured
editing uses the [documented `--json-*` hooks](README.md#structured-json). `--json-accent` controls its
menu/link colors and derived hover, focus, editing and selection colors together. `--json-selection`
can override the selection separately; `--json-scheme` defaults to `light`. These hooks apply on the
editor root or an ancestor. The adapter owns the underlying editor variables and scoped styles.

Literal palette colors live in the shared defaults. The Bosca SVG paths remain the original artwork;
its face colors are themeable. Caller-supplied badge `color`, heading `iconColor`, source content,
images and other projected content can intentionally provide their own colors. Git authentication,
ambient gradients, repository/diff/log composition and document resets remain in Git's CSS.

The compiled consumer's `/themes` page demonstrates base-token fallback, explicit control aliases,
shared sizing/focus and live overlay colors in independent roots, including native controls with
scripts disabled. Its other pages exercise forms, selection, tooltips, modal state, source and JSON
editing through real BML rendering and actions in production and development.
