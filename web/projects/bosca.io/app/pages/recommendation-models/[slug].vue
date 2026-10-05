<script setup lang="ts">
import RecommendationPlayground from '~/components/recommendation-models/RecommendationPlayground.vue'
import TextVectorMap from '~/components/recommendation-models/TextVectorMap.vue'
import startHere from '~/data/recommendation-models/start-here.md?raw'
import observations from '~/data/recommendation-models/observations.md?raw'
import datasets from '~/data/recommendation-models/datasets.md?raw'
import contentModel from '~/data/recommendation-models/content-model.md?raw'
import towers from '~/data/recommendation-models/towers.md?raw'
import training from '~/data/recommendation-models/training.md?raw'
import exportChapter from '~/data/recommendation-models/export.md?raw'

definePageMeta({
  layout: 'recommendation-models',
  validate: route => typeof route.params.slug === 'string'
    && ['start-here', 'observations', 'datasets', 'content-model', 'towers', 'training', 'export'].includes(route.params.slug)
})

const chapters = [
  { slug: 'start-here', title: 'Prologue: choose the next article', description: 'Meet a reader, try a tiny recommendation model, then see how its numbers work.', source: startHere },
  { slug: 'observations', title: 'Events to observations', description: 'How Bosca turns events and feedback into labels, weights, and positive pairs.', source: observations },
  { slug: 'datasets', title: 'Build the datasets', description: 'Features, boolean masks, and the four TensorFlow datasets.', source: datasets },
  { slug: 'content-model', title: 'Content model', description: 'Build the exact item similarity model without interaction history.', source: contentModel },
  { slug: 'towers', title: 'Personalized towers', description: 'Encode reader and item features as learned vectors.', source: towers },
  { slug: 'training', title: 'Train retrieval and ranking', description: 'Fit positive-pair retrieval, then the weighted ranking head.', source: training },
  { slug: 'export', title: 'Export and verify', description: 'Save, probe, publish, and test the serving models.', source: exportChapter }
] as const

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const index = computed(() => chapters.findIndex(chapter => chapter.slug === slug.value))
const chapter = computed(() => chapters[index.value]!)
const previous = computed(() => chapters[index.value - 1])
const next = computed(() => chapters[index.value + 1])
const prologueParts = startHere.split(/\n<!-- recommendation-(?:playground|text-map) -->\n/)
const prologueBefore = prologueParts[0] ?? ''
const prologueBetween = prologueParts[1] ?? ''
const prologueAfter = prologueParts[2] ?? ''

const parserOptions = {
  remark: { plugins: { 'remark-mdc': false as const } },
  rehype: {
    options: { allowDangerousHtml: false },
    plugins: { 'rehype-raw': false as const }
  }
}

useSeoMeta({
  title: () => `${chapter.value.title} · Recommendation Models`,
  description: () => chapter.value.description
})
</script>

<template>
  <div class="doc-content article model-chapter">
    <div class="chapter-progress">
      Chapter {{ index + 1 }} of {{ chapters.length }}
    </div>
    <template v-if="slug === 'start-here'">
      <MDC
        :value="prologueBefore"
        cache-key="recommendation-models-prologue-before"
        :parser-options="parserOptions"
        :tag="false"
      />
      <RecommendationPlayground />
      <MDC
        :value="prologueBetween"
        cache-key="recommendation-models-prologue-between"
        :parser-options="parserOptions"
        :tag="false"
      />
      <TextVectorMap />
      <MDC
        :value="prologueAfter"
        cache-key="recommendation-models-prologue-after"
        :parser-options="parserOptions"
        :tag="false"
      />
    </template>
    <MDC
      v-else
      :value="chapter.source"
      :cache-key="`recommendation-models-${slug}`"
      :parser-options="parserOptions"
      :tag="false"
    />
    <nav
      class="chapter-navigation"
      aria-label="Guide chapters"
    >
      <NuxtLink
        v-if="previous"
        :to="`/recommendation-models/${previous.slug}`"
        class="chapter-link"
      >
        <span>Previous</span>
        <strong>{{ previous.title }}</strong>
      </NuxtLink>
      <span v-else />
      <NuxtLink
        v-if="next"
        :to="`/recommendation-models/${next.slug}`"
        class="chapter-link next"
      >
        <span>Next</span>
        <strong>{{ next.title }}</strong>
      </NuxtLink>
    </nav>
  </div>
</template>

<style scoped>
.model-chapter { max-width: 920px; }
.chapter-progress { margin-bottom: 12px; color: #84c032; font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
.model-chapter :deep(:is(h1, h2, h3, h4) > a) { color: inherit; text-decoration: none; }
.model-chapter :deep(h1) { margin-top: 0; }
.model-chapter :deep(pre) { margin: 0 0 20px; padding: 16px; overflow-x: auto; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg-1); line-height: 1.65; }
.model-chapter :deep(pre code) { display: block; padding: 0; background: none; color: var(--fg-1); font-size: 13px; overflow-wrap: normal; white-space: pre; }
.model-chapter :deep(blockquote) { margin: 20px 0; padding: 12px 16px; border-left: 3px solid #84c032; background: color-mix(in srgb, #84c032 8%, transparent); color: var(--fg-1); }
.model-chapter :deep(blockquote > :last-child) { margin-bottom: 0; }
.model-chapter :deep(h3) { scroll-margin-top: 80px; }
.chapter-navigation { display: flex; justify-content: space-between; gap: 16px; margin-top: 48px; padding-top: 20px; border-top: 1px solid var(--line); }
.chapter-link { display: flex; flex-direction: column; gap: 4px; color: var(--fg-0); text-decoration: none; }
.chapter-link span { color: var(--fg-3); font-size: 12px; }
.chapter-link strong { font-size: 14px; }
.chapter-link.next { margin-left: auto; text-align: right; }
.chapter-link:hover strong { color: #84c032; }
</style>
