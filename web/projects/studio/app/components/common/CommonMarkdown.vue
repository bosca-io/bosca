<script setup lang="ts">
/**
 * Renders user-authored Markdown (PR descriptions, repository READMEs, …) as
 * formatted prose. The source is untrusted, so MDC component syntax and raw
 * HTML are disabled while ordinary CommonMark/GFM formatting is kept, along
 * with the renderer's URL/attribute validation.
 *
 * Typography (font size, color) is inherited from the host so each surface can
 * set its own scale; this component owns only the structural prose rules.
 */
const props = defineProps<{
  /** Markdown source. */
  value: string
  /** Stable key for MDC's parse cache; include anything whose change should re-render (e.g. an `updated` timestamp). */
  cacheKey?: string
}>()

const safeParserOptions = {
  remark: { plugins: { 'remark-mdc': false as const } },
  rehype: {
    options: { allowDangerousHtml: false },
    plugins: { 'rehype-raw': false as const },
  },
}

const cacheKey = computed(() => props.cacheKey ?? `markdown-${props.value.length}-${hashCode(props.value)}`)

// Cheap, stable hash so uncached callers still get a deterministic key per
// distinct source string.
function hashCode(input: string): number {
  let hash = 0
  for (let i = 0; i < input.length; i++) {
    hash = ((hash << 5) - hash + input.charCodeAt(i)) | 0
  }
  return hash
}
</script>

<template>
  <!-- MDC consumes its `class` as a component prop, so Vue cannot attach this
       component's scope attribute to the renderer's eventual root element. Own
       the styled DOM boundary here so the deep prose rules apply. -->
  <div class="markdown">
    <MDC
      :value="value"
      :cache-key="cacheKey"
      :parser-options="safeParserOptions"
      :tag="false" />
  </div>
</template>

<style scoped>
.markdown {
  overflow-wrap: anywhere;
}

.markdown :deep(> :first-child) { margin-top: 0; }
.markdown :deep(> :last-child) { margin-bottom: 0; }

.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3),
.markdown :deep(h4) {
  margin: 1.35em 0 0.55em;
  color: var(--fg-0);
  line-height: 1.25;
}

.markdown :deep(h1) { padding-bottom: 0.3em; border-bottom: 1px solid var(--line); font-size: 1.8em; }
.markdown :deep(h2) { padding-bottom: 0.3em; border-bottom: 1px solid var(--line); font-size: 1.4em; }
.markdown :deep(h3) { font-size: 1.15em; }
.markdown :deep(p) { margin: 0.75em 0; }
.markdown :deep(ul),
.markdown :deep(ol) { margin: 0.75em 0; padding-left: 1.6em; }
.markdown :deep(li + li) { margin-top: 0.25em; }
.markdown :deep(a) { color: var(--brand-2); text-decoration: underline; }
/* MDC wraps heading text in a self-referencing anchor; keep headings looking like headings. */
.markdown :deep(:is(h1, h2, h3, h4, h5, h6) > a) { color: inherit; text-decoration: none; }
.markdown :deep(pre) { padding: 14px; overflow: auto; background: var(--bg-0); border: 1px solid var(--line); border-radius: 7px; }
.markdown :deep(code) { font-family: var(--font-mono); font-size: 0.9em; }
.markdown :deep(:not(pre) > code) { padding: 0.15em 0.35em; background: var(--bg-3); border-radius: 4px; }
.markdown :deep(blockquote) { margin: 1em 0; padding-left: 1em; color: var(--fg-2); border-left: 3px solid var(--line-2); }
.markdown :deep(table) { width: 100%; margin: 1em 0; border-collapse: collapse; }
.markdown :deep(th),
.markdown :deep(td) { padding: 7px 10px; border: 1px solid var(--line); text-align: left; }
.markdown :deep(img) { max-width: 100%; height: auto; }
.markdown :deep(hr) { margin: 1.25em 0; border: 0; border-top: 1px solid var(--line); }
</style>
