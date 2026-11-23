<script setup lang="ts">
import type { HarnessSettings } from '~/utils/kitHarness'

const props = defineProps<{ settings: HarnessSettings; busy?: boolean }>()
const emit = defineEmits<{ change: [profile: string | null]; connections: [keys: string[]]; model: [model: string | null, reasoning: string | null] }>()
const options = computed(() => [
  { label: 'Managed defaults', value: '__managed__' },
  ...props.settings.choices.map(profile => ({ label: profile, value: profile })),
])
function selectProfile(value: string | string[] | null | undefined) {
  if (typeof value === 'string') emit('change', value === '__managed__' ? null : value)
}
const selectedConnections = computed(() => props.settings.requestedMcpServers ?? props.settings.mcpServers ?? [])
function selectConnection(key: string, event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  emit('connections', checked ? [...selectedConnections.value, key] : selectedConnections.value.filter(value => value !== key))
}
const modelOptions = computed(() => [
  { label: 'Managed model', value: '__managed__' },
  ...(props.settings.models ?? []).map(option => ({ label: option.name, value: option.value })),
])
const reasoningOptions = computed(() => [
  { label: 'Managed reasoning', value: '__managed__' },
  ...(props.settings.reasoningOptions ?? []).map(option => ({ label: option.name, value: option.value })),
])
const changingProfile = computed(() => props.settings.requestedProfile && props.settings.requestedProfile !== props.settings.effectiveProfile)
const changingModel = computed(() => props.settings.requestedModel && props.settings.requestedModel !== props.settings.model)
function selectModel(value: string | string[] | null | undefined) {
  if (typeof value === 'string') emit('model', value === '__managed__' ? null : value, null)
}
function selectReasoning(value: string | string[] | null | undefined) {
  if (typeof value === 'string') emit('model', props.settings.requestedModel ?? null, value === '__managed__' ? null : value)
}
</script>

<template>
  <div class="harness-settings">
    <span>Harness: {{ settings.effectiveProfile }} · {{ settings.source === 'CHAT' ? 'Custom' : 'Managed' }}</span>
    <Select
      v-if="settings.canOverride"
      :model-value="settings.requestedProfile ?? '__managed__'"
      :options="options"
      label="Harness for next turn"
      :disabled="busy"
      @update:model-value="selectProfile" />
    <span v-if="settings.requestedProfile && settings.requestedProfile !== settings.effectiveProfile">
      {{ settings.requestedProfile }} will apply to the next turn.
    </span>
    <span v-if="settings.model">Model: {{ settings.model }} · {{ settings.modelSource === 'CHAT' ? 'Custom' : 'Managed' }}</span>
    <span v-if="settings.reasoning">Reasoning: {{ settings.reasoning }} · {{ settings.reasoningSource === 'CHAT' ? 'Custom' : 'Managed' }}</span>
    <Select
      v-if="settings.canOverride && settings.models?.length"
      :model-value="settings.requestedModel ?? '__managed__'"
      :options="modelOptions"
      label="Model for next turn"
      :disabled="busy || Boolean(changingProfile)"
      @update:model-value="selectModel" />
    <Select
      v-if="settings.canOverride && settings.reasoningOptions?.length"
      :model-value="settings.requestedReasoning ?? '__managed__'"
      :options="reasoningOptions"
      label="Reasoning effort for next turn"
      :disabled="busy || Boolean(changingProfile) || Boolean(changingModel)"
      @update:model-value="selectReasoning" />
    <span v-if="changingModel">The model choice applies to the next turn. Its reasoning choices will then be available.</span>
    <fieldset v-if="settings.canOverride && settings.mcpOptions?.length" :disabled="busy">
      <legend>Connections for next turn</legend>
      <label v-for="connection in settings.mcpOptions" :key="connection.key">
        <input
          type="checkbox"
          :checked="selectedConnections.includes(connection.key) || connection.required"
          :disabled="connection.required"
          @change="selectConnection(connection.key, $event)">
        {{ connection.name }}<span v-if="connection.required"> (required)</span>
      </label>
    </fieldset>
    <span v-else-if="settings.mcpServers?.length">Connections: {{ settings.mcpServers.join(', ') }}</span>
    <span v-for="message in settings.mcpNotices" :key="message" role="status">{{ message }}</span>
    <span v-if="settings.notice" role="status">{{ settings.notice }}</span>
  </div>
</template>

<style scoped>
.harness-settings { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; color: var(--fg-3); font-size: 12px; }
.harness-settings fieldset { display: flex; gap: 12px; border: 0; padding: 0; }
.harness-settings label { display: flex; gap: 4px; align-items: center; }
</style>
