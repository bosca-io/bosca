<script setup lang="ts">
import type { HarnessContext } from '~/utils/kitHarness'
const props = withDefaults(defineProps<{
  context: HarnessContext
  attachments?: string[]
  collectionAttachments?: string[]
  saving?: boolean
}>(), { attachments: () => [], collectionAttachments: () => [], saving: false })
const emit = defineEmits<{
  addContent: []
  addCollection: []
  addWorkOps: []
  addGit: []
  clear: []
  remove: [kind: string, id: string, ref?: string | null, path?: string | null]
}>()
function selected(kind: string, id: string) {
  if (kind === 'collection') return props.collectionAttachments.includes(id)
  return ['metadata', 'memory'].includes(kind) && props.attachments.includes(id)
}
function remove(source: HarnessContext['sources'][number]) {
  if (source.kind === 'git_file') emit('remove', source.kind, source.id, source.ref, source.path)
  else emit('remove', source.kind, source.id)
}
function label(source: HarnessContext['sources'][number]) {
  return source.kind === 'git_file' && source.ref ? `${source.title} (${source.ref})` : source.title
}
</script>

<template>
  <details class="harness-context">
    <summary>Context · {{ context.sources.length }} {{ context.sources.length === 1 ? 'source' : 'sources' }}</summary>
    <ul v-if="context.sources.length" aria-label="Current context sources">
      <li v-for="source in context.sources" :key="`${source.kind}:${source.id}:${source.ref ?? ''}:${source.path ?? ''}`">
        {{ label(source) }}<span v-if="source.kind === 'collection'"> · Collection</span>
        <template v-if="source.kind === 'memory'">
          <span> · Saved memory</span>
          <NuxtLink :to="`/cms/editor/${source.id}`">View or edit</NuxtLink>
        </template>
        <Button
          v-if="source.selected || selected(source.kind, source.id)"
          size="sm"
          variant="ghost"
          :disabled="saving"
          :aria-label="`Remove ${label(source)} from context`"
          @click="remove(source)">Remove</Button>
      </li>
    </ul>
    <p v-else>No selected sources are currently available.</p>
    <div class="context-actions">
      <Button
        size="sm"
        variant="ghost"
        :disabled="saving"
        @click="emit('addContent')">Add content</Button>
      <Button
        size="sm"
        variant="ghost"
        :disabled="saving"
        @click="emit('addCollection')">Add collection</Button>
      <Button
        size="sm"
        variant="ghost"
        :disabled="saving"
        @click="emit('addWorkOps')">Add WorkOps</Button>
      <Button
        size="sm"
        variant="ghost"
        :disabled="saving"
        @click="emit('addGit')">Add Git file</Button>
      <Button
        v-if="context.selectionCount"
        size="sm"
        variant="ghost"
        :disabled="saving"
        @click="emit('clear')">Clear WorkOps and Git selections</Button>
    </div>
    <p>Selection changes apply to the next message.</p>
    <p v-if="context.sources.some(source => source.kind === 'memory')">Saved memories remain when a conversation is deleted. Ask to forget a memory to remove it from future context.</p>
    <p v-if="context.integrations.includes('bosca')">Bosca tools are available.</p>
    <p v-for="notice in context.notices" :key="notice" role="status">{{ notice }}</p>
  </details>
</template>

<style scoped>
.harness-context { padding: 8px 0; color: var(--fg-2); font-size: 12.5px; }
.harness-context summary { cursor: pointer; }
.harness-context ul { padding-left: 20px; }
.harness-context p { margin: 6px 0; }
.context-actions { display: flex; gap: 8px; }
.harness-context span { color: var(--fg-3); }
.harness-context a { margin-left: 8px; }
</style>
