<script lang="ts">
import { defineComponent, h, type VNode } from 'vue'

/**
 * A minimal, read-only renderer for the Bosca document model (ProseMirror/TipTap node tree) used to
 * preview ingested feed items in the Feeds subsystem. Studio's full editor is collaboration/Yjs-bound
 * and far heavier than a preview needs, so this walks the node tree with render functions (no `v-html`,
 * so text is escaped by Vue) and covers the node/mark set feed normalization produces. Pass the raw
 * `Document.content` JSON — the `{ document: ... }` wrapper is unwrapped here.
 */

interface Mark { type?: string; attrs?: Record<string, unknown> }
interface DocNode {
  type?: string
  text?: string
  attrs?: Record<string, unknown>
  content?: DocNode[]
  marks?: Mark[]
}

function renderText(node: DocNode): VNode | string {
  let acc: VNode | string = node.text ?? ''
  for (const mark of node.marks ?? []) {
    switch (mark.type) {
      case 'bold': acc = h('strong', acc); break
      case 'italic': acc = h('em', acc); break
      case 'code': acc = h('code', acc); break
      case 'strike': acc = h('s', acc); break
      case 'underline': acc = h('u', acc); break
      case 'superscript': acc = h('sup', acc); break
      case 'subscript': acc = h('sub', acc); break
      case 'link':
        acc = h('a', {
          href: String(mark.attrs?.href ?? '#'),
          target: '_blank',
          rel: 'noopener noreferrer',
        }, acc)
        break
      case 'hidden': return '' // hidden marks are not shown in the preview
      default: break
    }
  }
  return acc
}

function renderChildren(nodes?: DocNode[]): (VNode | string)[] {
  const out: (VNode | string)[] = []
  for (const child of nodes ?? []) {
    const rendered = renderNode(child)
    if (rendered !== null) out.push(rendered)
  }
  return out
}

function renderNode(node: DocNode): VNode | string | null {
  switch (node.type) {
    case 'text': return renderText(node)
    case 'paragraph': return h('p', renderChildren(node.content))
    case 'heading': {
      const level = Math.min(Math.max(Number(node.attrs?.level ?? 2), 1), 6)
      return h(`h${level}`, renderChildren(node.content))
    }
    case 'bulletList': return h('ul', renderChildren(node.content))
    case 'orderedList':
      return h('ol', node.attrs?.start ? { start: Number(node.attrs.start) } : {}, renderChildren(node.content))
    case 'listItem': return h('li', renderChildren(node.content))
    case 'blockquote': return h('blockquote', renderChildren(node.content))
    case 'codeBlock': return h('pre', [h('code', renderChildren(node.content))])
    case 'horizontalRule': return h('hr')
    case 'hardBreak': return h('br')
    case 'image':
      return h('img', { src: String(node.attrs?.src ?? ''), alt: String(node.attrs?.alt ?? '') })
    case 'doc': return h('div', renderChildren(node.content))
    default: {
      const kids = renderChildren(node.content)
      return kids.length ? h('div', kids) : null
    }
  }
}

export default defineComponent({
  name: 'FeedsDocumentView',
  props: {
    // `null` runtime type accepts any value — the content is an opaque document JSON tree
    // (object wrapper) or its string form, narrowed inside the render function.
    content: { type: null, default: null },
    /** Shown when there is no renderable document — e.g. a feed that ships only a short summary/dek. */
    fallback: { type: String, default: '' },
  },
  setup(props) {
    return () => {
      let root: unknown = props.content
      if (typeof root === 'string') {
        try { root = JSON.parse(root) }
        catch { return h('p', { class: 'fd-empty' }, 'Content unavailable.') }
      }
      const wrapper = root as { document?: DocNode } | DocNode | null
      const doc = (wrapper && 'document' in wrapper ? wrapper.document : wrapper) as DocNode | undefined
      if (!doc || !Array.isArray(doc.content) || doc.content.length === 0) {
        const fb = props.fallback.trim()
        if (fb) return h('div', { class: 'fd-doc' }, [h('p', { class: 'fd-fallback' }, fb)])
        return h('p', { class: 'fd-empty' }, 'No readable content — open the original to read this item.')
      }
      return h('div', { class: 'fd-doc' }, renderChildren(doc.content))
    }
  },
})
</script>

<style scoped>
.fd-doc { color: var(--fg-1); font-size: 15px; line-height: 1.7; }
.fd-doc :deep(h1),
.fd-doc :deep(h2),
.fd-doc :deep(h3),
.fd-doc :deep(h4),
.fd-doc :deep(h5),
.fd-doc :deep(h6) { color: var(--fg-1); line-height: 1.3; margin: 1.3em 0 0.5em; font-weight: 600; }
.fd-doc :deep(h1) { font-size: 1.55em; }
.fd-doc :deep(h2) { font-size: 1.3em; }
.fd-doc :deep(h3) { font-size: 1.15em; }
.fd-doc :deep(p) { margin: 0 0 1em; }
.fd-doc :deep(a) { color: #f97316; text-decoration: underline; }
.fd-doc :deep(ul),
.fd-doc :deep(ol) { margin: 0 0 1em 1.3em; }
.fd-doc :deep(li) { margin: 0.25em 0; }
.fd-doc :deep(blockquote) {
  margin: 0 0 1em;
  padding-left: 14px;
  border-left: 3px solid var(--line-2);
  color: var(--fg-2);
}
.fd-doc :deep(pre) {
  background: var(--bg-3);
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 13px;
  margin: 0 0 1em;
}
.fd-doc :deep(code) { font-family: var(--font-mono, monospace); font-size: 0.9em; }
.fd-doc :deep(img) { max-width: 100%; border-radius: 8px; margin: 0.5em 0; }
.fd-doc :deep(hr) { border: none; border-top: 1px solid var(--line-2); margin: 1.5em 0; }
.fd-fallback { color: var(--fg-2); font-style: italic; }
.fd-empty { color: var(--fg-3); font-size: 13px; }
</style>
