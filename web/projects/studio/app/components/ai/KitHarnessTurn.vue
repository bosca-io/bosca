<script setup lang="ts">
import type { HarnessEvent } from '~/utils/kitHarness'
defineProps<{ event: HarnessEvent }>()
const outcomes: Record<string, string> = {
  CANCELLED: 'Cancelled', INTERRUPTED: 'Interrupted — the outcome could not be confirmed', FAILED: 'The turn failed',
}
</script>

<template>
  <div class="harness-turn">
    <KitMessageContent v-if="event.text" :text="event.text" />
    <ol v-if="event.plan.length" class="harness-plan" aria-label="Plan">
      <li v-for="(entry, index) in event.plan" :key="index" :class="{ completed: entry.status === 'completed' }">
        {{ entry.content }}<span v-if="entry.status === 'in_progress'"> — in progress</span>
      </li>
    </ol>
    <details v-for="tool in event.tools" :key="tool.toolCallId" class="harness-tool">
      <summary>{{ tool.title || 'Tool operation' }} <span>{{ tool.status?.replaceAll('_', ' ') }}</span></summary>
      <KitHarnessContent v-for="(content, index) in tool.content || []" :key="index" :content="content" />
    </details>
    <KitHarnessContent v-for="(content, index) in event.content" :key="index" :content="content" />
    <p v-if="outcomes[event.status]" class="harness-outcome" role="status">{{ outcomes[event.status] }}</p>
  </div>
</template>

<style scoped>
.harness-turn { display: flex; flex-direction: column; gap: 10px; font-size: 14px; line-height: 1.55; }
.harness-plan { padding-left: 20px; margin: 0; }
.harness-plan .completed { color: var(--fg-3); text-decoration: line-through; }
.harness-tool { border: 1px solid var(--line); border-radius: var(--r-sm); padding: 8px 10px; }
.harness-tool summary { cursor: pointer; font-size: 13px; }
.harness-tool summary span { color: var(--fg-3); margin-left: 8px; }
.harness-tool[open] summary { margin-bottom: 8px; }
.harness-outcome { margin: 0; color: var(--fg-2); font-size: 12.5px; }
</style>
