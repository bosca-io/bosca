<script setup lang="ts">
import KitGraphQLResult from './KitGraphQLResult.vue'
import { harnessAnalyticsResource, harnessGraphQLResource, safeHarnessLink } from '~/utils/kitHarness'
const props = defineProps<{ content: Record<string, unknown> }>()
const block = computed(() => props.content.type === 'content' ? props.content.content as Record<string, unknown> : props.content)
const resource = computed(() => block.value?.resource as Record<string, unknown> | undefined)
const analytics = computed(() => harnessAnalyticsResource(resource.value))
const graphql = computed(() => harnessGraphQLResource(resource.value))
const text = computed(() => typeof block.value?.text === 'string' ? block.value.text : typeof resource.value?.text === 'string' ? resource.value.text : '')
const link = computed(() => safeHarnessLink(block.value?.uri ?? resource.value?.uri))
const image = computed(() => {
  const b = block.value
  if (b?.type !== 'image' || typeof b.data !== 'string' || !/^[A-Za-z0-9+/=\r\n]+$/.test(b.data)) return null
  return ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/avif'].includes(String(b.mimeType))
    ? `data:${b.mimeType};base64,${b.data}` : null
})
</script>

<template>
  <div class="harness-content">
    <template v-if="analytics">
      <KitAnalyticsResult
        :name="analytics.title"
        :visualization-type="analytics.visualizationType"
        :configuration="analytics.configuration"
        :data="analytics.data" />
      <p class="query-evidence">
        {{ analytics.title }} · {{ analytics.data.length }} {{ analytics.data.length === 1 ? 'row' : 'rows' }}<span v-if="analytics.truncated"> · Results truncated</span><span v-if="analytics.cached"> · Cached result</span><span v-if="analytics.stale"> · Refresh overdue</span><span v-if="analytics.refreshedAt"> · Refreshed {{ analytics.refreshedAt }}</span>
      </p>
      <details v-if="analytics.sql">
        <summary>Executed SQL · {{ analytics.durationMs }} ms · {{ analytics.executedAt }}</summary>
        <pre>{{ analytics.sql }}</pre>
        <template v-if="analytics.parameters?.length">
          <p>Bound parameters</p>
          <pre>{{ JSON.stringify(analytics.parameters, null, 2) }}</pre>
        </template>
      </details>
      <NuxtLink v-if="analytics.queryHref" :to="analytics.queryHref">Open saved query</NuxtLink>
    </template>
    <KitGraphQLResult v-else-if="graphql" :data="graphql" />
    <KitMessageContent v-else-if="text" :text="text" />
    <img
      v-if="image"
      :src="image"
      alt="Image returned by Kit"
      loading="lazy">
    <NuxtLink v-if="link?.startsWith('/')" :to="link">{{ block.title || block.name || 'Open resource' }}</NuxtLink>
    <a
      v-else-if="link"
      :href="link"
      target="_blank"
      rel="noopener noreferrer">{{ block.name || 'Open resource' }}</a>
    <details v-if="block.type === 'diff'">
      <summary>Changes to {{ block.path }}</summary>
      <pre v-if="typeof block.oldText === 'string'">{{ block.oldText }}</pre>
      <pre>{{ block.newText }}</pre>
    </details>
  </div>
</template>

<style scoped>
.harness-content { display: flex; flex-direction: column; gap: 8px; overflow-wrap: anywhere; }
.harness-content img { max-width: 100%; max-height: 520px; object-fit: contain; border-radius: var(--r-sm); }
.harness-content a { color: var(--accent, var(--fg-0)); text-decoration: underline; }
.harness-content pre { max-height: 300px; overflow: auto; font-size: 12px; white-space: pre-wrap; }
.query-evidence { margin: 0; font-size: 12px; color: var(--fg-3); }
</style>
