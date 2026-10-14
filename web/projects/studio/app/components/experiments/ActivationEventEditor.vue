<script setup lang="ts">
import type { ActivationEventFilter } from '~/utils/goalActivation'
import { splitIdentifiers } from '~/utils/goalActivation'
const model = defineModel<ActivationEventFilter>({ required: true })
</script>
<template>
  <div class="event-fields">
    <label>Event<select v-model="model.eventType"><option :value="null">Any activity</option><option>Impression</option><option>Interaction</option><option>Completion</option><option>Session</option><option>Installation</option><option>Assignment</option><option>Error</option></select></label>
    <label>Element type<input v-model="model.elementType" placeholder="page"></label>
    <label>Element ID<input v-model="model.elementId"></label>
    <label>Element ID contains<input v-model="model.elementIdContains" placeholder="article-inline-rec-"></label>
    <label>Exact page path<input v-model="model.pagePath"></label>
    <label>Page path prefixes<input :value="model.pagePathPrefixes?.join(', ')" @input="model.pagePathPrefixes = splitIdentifiers(($event.target as HTMLInputElement).value)"></label>
    <label>Item extra key<input v-model="model.itemExtraKey" placeholder="recommendation_location"></label>
    <label class="check"><input type="checkbox" :checked="model.itemExtraValue != null" @change="model.itemExtraValue = ($event.target as HTMLInputElement).checked ? '' : null">Match an exact item extra value</label>
    <label v-if="model.itemExtraValue != null">Item extra value<input v-model="model.itemExtraValue"></label>
    <label>Application IDs<input :value="model.applicationIds?.join(', ')" placeholder="Inherit activation scope" @input="model.applicationIds = splitIdentifiers(($event.target as HTMLInputElement).value)"></label>
    <label class="check"><input v-model="model.recommendationCard" type="checkbox">Recommendation card with a content target</label>
    <p>Card impressions require at least 50% visibility for one second.</p>
  </div>
</template>
<style scoped>
.event-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
label { display: flex; flex-direction: column; gap: 5px; font-size: 12px; }
input, select { padding: 8px; background: var(--bg-2); color: var(--fg-1); border: 1px solid var(--border); border-radius: 6px; }
.check { flex-direction: row; align-items: center; }
p { grid-column: 1 / -1; margin: 0; color: var(--fg-3); font-size: 12px; }
</style>
