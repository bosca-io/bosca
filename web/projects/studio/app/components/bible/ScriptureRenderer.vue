<script setup lang="ts">
/**
 * Recursive renderer for the Bible chapter component tree.
 * The backend uses a discriminated union with a `_` field:
 *   cc = container, t = text, vs = verse start, ve = verse end.
 * Styles are referenced via `sr` nodes with an `id` that maps into the Bible's style definitions.
 */

interface StyleDef {
  id: string
  reference?: boolean
  fontWeight?: string
  align?: string
  textIndent?: { size?: number; unit?: string } | null
}

interface SizeUnit {
  size?: number
  unit?: string
}

interface StyleRef {
  _: 'sr'
  id: string
  align?: string
  textIndent?: SizeUnit | null
  margin?: {
    top?: SizeUnit | null
    left?: SizeUnit | null
    right?: SizeUnit | null
    bottom?: SizeUnit | null
  } | null
}

interface ComponentNode {
  _: string
  type?: string
  components?: ComponentNode[]
  style?: StyleRef | null
  text?: string
  reference?: { usfm: string; human?: string; humanShort?: string }
}

const props = defineProps<{
  nodes: ComponentNode[]
  styles: Map<string, StyleDef>
  accent: string
}>()

function cssSize(s: SizeUnit): string {
  return `${s.size}${s.unit === 'INCH' ? 'in' : s.unit === 'POINT' ? 'pt' : s.unit === 'PERCENT' ? '%' : 'em'}`
}

function resolveStyle(node: ComponentNode): Record<string, string> {
  const css: Record<string, string> = {}
  const styleRef = node.style
  if (!styleRef) return css
  const s = props.styles.get(styleRef.id)
  const align = styleRef.align || s?.align
  if (s?.fontWeight) css.fontWeight = s.fontWeight
  if (align) css.textAlign = align
  const indent = styleRef.textIndent || s?.textIndent
  if (indent?.size) css.textIndent = cssSize(indent)
  const margin = styleRef.margin
  if (margin) {
    if (margin.top?.size) css.marginTop = cssSize(margin.top)
    if (margin.right?.size) css.marginRight = cssSize(margin.right)
    if (margin.bottom?.size) css.marginBottom = cssSize(margin.bottom)
    if (margin.left?.size) css.marginLeft = cssSize(margin.left)
  }
  return css
}

function isReference(node: ComponentNode): boolean {
  if (!node.style) return false
  return props.styles.get(node.style.id)?.reference ?? false
}

// The compiler serializes character markup as DIV containers, so the renderer
// must restore the inline semantics defined by Bosca's Bible style enums.
const inlineStyles = new Set([
  'w', 'rb', 'va', 'vp', 'ca', 'qac', 'qs', 'add', 'addpn', 'bk', 'dc',
  'efm', 'fm', 'k', 'nd', 'ndx', 'ord', 'pn', 'png', 'pro', 'qt', 'rq',
  'sig', 'sls', 'tl', 'wg', 'wh', 'wa', 'wj', 'xt', 'jmp', 'no', 'it',
  'bd', 'bdit', 'em', 'sc', 'sup',
  'xo', 'xop', 'xta', 'xk', 'xq', 'xot', 'xnt', 'xdc',
  'ior', 'iqt',
  'fr', 'cat', 'ft', 'fk', 'fq', 'fqa', 'fl', 'fw', 'fp', 'fv', 'fdc',
  'litl', 'lik', 'liv', 'liv1', 'liv2', 'liv3', 'liv4', 'liv5',
])

function containerTag(type: string, styleId: string): string {
  if (inlineStyles.has(styleId)) return 'span'
  switch (type) {
    case 'PARAGRAPH': return 'p'
    case 'SPAN': return 'span'
    case 'ROW': return 'div'
    case 'COLUMN': return 'div'
    case 'DIV': return 'div'
    case 'TABLE': return 'div'
    default: return 'div'
  }
}

function containerClass(type: string, styleId: string): string | string[] {
  if (inlineStyles.has(styleId)) return ['sc-span', `sc-style-${styleId}`]
  switch (type) {
    case 'PARAGRAPH': return 'sc-paragraph'
    case 'SPAN': return 'sc-span'
    case 'ROW': return 'sc-row'
    case 'COLUMN': return 'sc-column'
    default: return 'sc-block'
  }
}

const footnoteStyles = new Set(['f', 'fe', 'ef', 'x', 'ex'])

function isFootnote(node: ComponentNode): boolean {
  return footnoteStyles.has(node.style?.id ?? '')
}

function verseNumber(usfm: string): string {
  const parts = usfm.split('.')
  return parts[parts.length - 1] ?? ''
}
</script>

<template>
  <template v-for="(node, i) in nodes" :key="i">
    <!-- Container (cc) -->
    <component
      :is="containerTag(node.type!, node.style?.id ?? '')"
      v-if="node._ === 'cc' && !isFootnote(node)"
      :class="containerClass(node.type!, node.style?.id ?? '')"
      :style="resolveStyle(node)"
    >
      <ScriptureRenderer
        :nodes="node.components ?? []"
        :styles="styles"
        :accent="accent"
      />
    </component>

    <!-- Text (t) -->
    <span
      v-else-if="node._ === 't'"
      :class="{ 'sc-text': true, 'sc-ref-text': isReference(node) }"
      :style="resolveStyle(node)"
    >{{ node.text }}</span>

    <!-- Verse start (vs) -->
    <sup
      v-else-if="node._ === 'vs' && node.reference"
      class="sc-verse-num"
      :style="{ color: accent }"
      :title="node.reference.human"
    >{{ verseNumber(node.reference.usfm) }}</sup>
  </template>
</template>

<style scoped>
.sc-paragraph {
  margin: 0;
  padding: 4px 0;
  line-height: 1.85;
}

.sc-span {
  display: inline;
}

.sc-style-it,
.sc-style-bdit,
.sc-style-em {
  font-style: italic;
}

.sc-style-bd,
.sc-style-bdit {
  font-weight: 700;
}

.sc-style-sc {
  font-variant: small-caps;
}

.sc-style-sup {
  font-size: 0.7em;
  vertical-align: super;
}

.sc-row {
  display: flex;
  gap: 8px;
}

.sc-column {
  display: flex;
  flex-direction: column;
}

.sc-block {
  display: block;
}

.sc-text {
  color: var(--fg-0);
}

.sc-ref-text {
  font-weight: 600;
}

.sc-verse-num {
  font-size: 0.7em;
  font-weight: 700;
  margin-right: 2px;
  user-select: none;
  font-family: var(--font-mono, monospace);
  font-feature-settings: 'tnum' 1;
}
</style>
