<script setup lang="ts">
import anchorExample from '~/data/bml-reference/examples/anchor.bml?raw'
import cardExample from '~/data/bml-reference/examples/card.bml?raw'
import gettingStarted from '~/data/bml-reference/getting-started.md?raw'
import libraries from '~/data/bml-reference/libraries.md?raw'
import libraryProviders from '~/data/bml-reference/library-providers.md?raw'
import libraryLocalization from '~/data/bml-reference/library-localization.md?raw'
import libraryClientTypes from '~/data/bml-reference/library-client-types.md?raw'
import designLibrary from '~/data/bml-reference/design-library.md?raw'
import designTokens from '~/data/bml-reference/design-tokens.md?raw'
import designMigration from '~/data/bml-reference/design-migration.md?raw'
import grammar from '~/data/bml-reference/grammar.md?raw'
import examplesOverview from '~/data/bml-reference/examples.md?raw'
import islands from '~/data/bml-reference/islands.md?raw'
import listItemExample from '~/data/bml-reference/examples/list-item.bml?raw'
import listOpsExample from '~/data/bml-reference/examples/list-ops.kt?raw'
import listsExample from '~/data/bml-reference/examples/lists.bml?raw'
import siteHeaderExample from '~/data/bml-reference/examples/site-header.bml?raw'
import tagReference from '~/data/bml-reference/tag-reference.md?raw'
import taskListExample from '~/data/bml-reference/examples/task-list.bml?raw'
import taskListViewModelExample from '~/data/bml-reference/examples/task-list-view-model.kt?raw'
import welcomeEmailExample from '~/data/bml-reference/examples/welcome-email.bml?raw'

definePageMeta({
  layout: 'bml-reference',
  validate: route => typeof route.params.slug === 'string'
    && ['getting-started', 'grammar', 'tag-reference', 'islands', 'examples', 'libraries',
      'library-providers', 'library-localization', 'library-client-types', 'design-library',
      'design-tokens', 'design-migration'].includes(route.params.slug)
})

interface BmlDoc {
  title: string
  description: string
  source: string
}

const exampleFiles = [
  ['anchor.bml', 'bml', anchorExample],
  ['card.bml', 'bml', cardExample],
  ['list-item.bml', 'bml', listItemExample],
  ['lists.bml', 'bml', listsExample],
  ['list-ops.kt', 'kotlin', listOpsExample],
  ['task-list.bml', 'bml', taskListExample],
  ['task-list-view-model.kt', 'kotlin', taskListViewModelExample],
  ['site-header.bml', 'bml', siteHeaderExample],
  ['welcome-email.bml', 'bml', welcomeEmailExample]
] as const

const examples = [
  examplesOverview.trimEnd(),
  '## Source files',
  ...exampleFiles.flatMap(([name, language, source]) => [
    `### \`${name}\``,
    `\`\`\`${language}\n${source.trimEnd()}\n\`\`\``
  ])
].join('\n\n')

const docs = {
  'getting-started': {
    title: 'Getting Started with BML',
    description: 'Set up, build, run, and develop a Bosca Markup Language project.',
    source: gettingStarted
  },
  'grammar': {
    title: 'BML Grammar',
    description: 'The complete syntax and semantic reference for Bosca Markup Language.',
    source: grammar
  },
  'tag-reference': {
    title: 'BML Tag Reference',
    description: 'A concise reference for BML tags, attributes, expressions, and asset tiers.',
    source: tagReference
  },
  'islands': {
    title: 'BML Islands',
    description: 'Client islands, deferred rendering, live state, actions, and server re-rendering in BML.',
    source: islands
  },
  'examples': {
    title: 'BML Examples',
    description: 'Representative BML pages, components, islands, contracts, ViewModels, and message markup.',
    source: examples
  },
  'libraries': {
    title: 'Compiled BML Libraries',
    description: 'Build and consume compiled component libraries with project-wide imports, original-source navigation, and development reload.',
    source: libraries
  },
  'library-providers': {
    title: 'BML Library Providers',
    description: 'Compose compiled library providers with application-owned services and host bindings.',
    source: libraryProviders
  },
  'library-localization': {
    title: 'BML Library Localization',
    description: 'Include library messages in the consuming application’s localization catalog.',
    source: libraryLocalization
  },
  'library-client-types': {
    title: 'BML Library Client Types',
    description: 'Publish TypeScript event declarations with compiled components and use them in application adapters.',
    source: libraryClientTypes
  },
  'design-library': {
    title: 'Bosca Design Library',
    description: 'Use shared BML components with short tags, native semantics, application state, and consumer CSS.',
    source: designLibrary
  },
  'design-tokens': {
    title: 'Design Library CSS Variables',
    description: 'Set component colors, fonts, spacing, focus, surfaces, and editor styles in consuming application CSS.',
    source: designTokens
  },
  'design-migration': {
    title: 'Migrating Git UI Components',
    description: 'Map the original Git UI components to shared exports and retain application-owned state, styles, and actions.',
    source: designMigration
  }
} satisfies Record<string, BmlDoc>

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const doc = computed(() => docs[slug.value as keyof typeof docs])

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
  <div class="doc-content article bml-reference">
    <MDC
      :value="doc.source"
      :cache-key="`bml-reference-${slug}`"
      :parser-options="parserOptions"
      :tag="false"
    />
  </div>
</template>

<style scoped>
.bml-reference {
  max-width: 920px;
}

.bml-reference :deep(:is(h1, h2, h3, h4, h5, h6) > a) {
  color: inherit;
  text-decoration: none;
}

.bml-reference :deep(h1) {
  margin-top: 0;
}

.bml-reference :deep(h4) {
  margin: 24px 0 10px;
  font-size: 15px;
}

.bml-reference :deep(pre) {
  margin: 0 0 20px;
  padding: 16px;
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-1);
  line-height: 1.65;
}

.bml-reference :deep(pre code) {
  display: block;
  padding: 0;
  background: none;
  color: var(--fg-1);
  font-size: 13px;
  overflow-wrap: normal;
  white-space: pre;
}

[data-theme="light"] .bml-reference :deep(:not(pre) > code) {
  color: var(--fg-1);
}

.bml-reference :deep(blockquote) {
  margin: 20px 0;
  padding: 12px 16px;
  border-left: 3px solid var(--info);
  background: color-mix(in srgb, var(--info) 8%, transparent);
  color: var(--fg-1);
}

.bml-reference :deep(blockquote > :last-child) {
  margin-bottom: 0;
}

.bml-reference :deep(hr) {
  margin: 40px 0;
  border: 0;
  border-top: 1px solid var(--line);
}
</style>
