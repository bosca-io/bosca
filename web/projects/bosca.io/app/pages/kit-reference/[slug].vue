<script setup lang="ts">
import { KIT_DOCS } from '~/data/kit-reference'

definePageMeta({
  layout: 'kit-reference',
  validate: route => typeof route.params.slug === 'string' && Object.hasOwn(KIT_DOCS, route.params.slug)
})

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const doc = computed(() => KIT_DOCS[slug.value as keyof typeof KIT_DOCS])
const parserOptions = {
  remark: { plugins: { 'remark-mdc': false as const } },
  rehype: {
    options: { allowDangerousHtml: false },
    plugins: { 'rehype-raw': false as const }
  }
}

useSeoMeta({
  title: () => doc.value.title,
  description: () => doc.value.description
})
</script>

<template>
  <div class="doc-content article kit-reference">
    <MDC
      :value="doc.source"
      :cache-key="`kit-reference-${slug}`"
      :parser-options="parserOptions"
      :tag="false"
    />
  </div>
</template>

<style scoped>
.kit-reference { max-width: 920px; }
.kit-reference :deep(:is(h1, h2, h3, h4, h5, h6) > a) { color: inherit; text-decoration: none; }
.kit-reference :deep(h1) { margin-top: 0; }
.kit-reference :deep(h4) { margin: 24px 0 10px; font-size: 15px; }
.kit-reference :deep(pre) {
  margin: 0 0 20px;
  padding: 16px;
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-1);
  line-height: 1.65;
}
.kit-reference :deep(pre code) {
  display: block;
  padding: 0;
  background: none;
  color: var(--fg-1);
  font-size: 13px;
  overflow-wrap: normal;
  white-space: pre;
}
.kit-reference :deep(blockquote) {
  margin: 20px 0;
  padding: 12px 16px;
  border-left: 3px solid var(--info);
  background: color-mix(in srgb, var(--info) 8%, transparent);
  color: var(--fg-1);
}
.kit-reference :deep(blockquote > :last-child) { margin-bottom: 0; }
.kit-reference :deep(hr) { margin: 40px 0; border: 0; border-top: 1px solid var(--line); }
.kit-reference :deep(img) { max-width: 100%; height: auto; }
</style>
