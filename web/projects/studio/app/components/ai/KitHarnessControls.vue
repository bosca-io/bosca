<script setup lang="ts">
import { harnessStateLabel } from '~/utils/kitHarness'
import type { HarnessApproval, HarnessState } from '~/utils/kitHarness'
defineProps<{ state?: HarnessState | null; approvals: HarnessApproval[]; processing: boolean; busy: boolean }>()
defineEmits<{ cancel: []; activate: []; archive: []; decide: [id: string, optionId: string] }>()
</script>

<template>
  <section v-if="state" class="harness-controls" aria-label="Session controls">
    <div class="harness-state">
      <span role="status">{{ harnessStateLabel(state) }}</span>
      <Button
        v-if="!processing && ['STOPPED', 'FAILED'].includes(state.state)"
        size="sm"
        :disabled="busy"
        @click="$emit('activate')"
      >{{ state.state === 'FAILED' ? 'Retry session' : 'Resume session' }}</Button>
      <Button
        v-if="!processing && state.state === 'READY'"
        size="sm"
        :disabled="busy"
        @click="$emit('archive')"
      >Archive session</Button>
      <Button
        v-if="processing || ['STARTING', 'RESUMING'].includes(state.state)"
        size="sm"
        :disabled="busy || state.cancellationRequested"
        @click="$emit('cancel')"
      >{{ state.cancellationRequested ? 'Stopping…' : 'Stop' }}</Button>
    </div>
    <p v-if="state.failure" class="harness-failure" role="alert">{{ state.failure }}</p>
    <article v-for="approval in approvals" :key="approval.id" class="harness-approval">
      <strong>{{ approval.request.toolCall.title || 'Permission requested' }}</strong>
      <p v-if="approval.request.toolCall.locations?.length">{{ approval.request.toolCall.locations.map(location => location.path).join(', ') }}</p>
      <div class="harness-decisions">
        <Button
          v-for="option in approval.request.permissions"
          :key="option.optionId"
          size="sm"
          :disabled="busy || state.cancellationRequested"
          @click="$emit('decide', approval.id, option.optionId)"
        >{{ option.name }}</Button>
      </div>
    </article>
  </section>
</template>

<style scoped>
.harness-controls { display: flex; flex-direction: column; gap: 10px; padding: 12px 0; }
.harness-state { display: flex; align-items: center; justify-content: space-between; color: var(--fg-2); font-size: 13px; }
.harness-failure { margin: 0; font-size: 13px; color: var(--fg-2); }
.harness-approval { border: 1px solid var(--line); background: var(--bg-1); border-radius: var(--r-sm); padding: 12px; font-size: 13px; }
.harness-approval p { overflow-wrap: anywhere; }
.harness-decisions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
</style>
