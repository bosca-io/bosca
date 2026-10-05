<script setup lang="ts">
import type { GlassTableColumn } from '@bosca/ui'

export interface MatrixNotificationType {
  key: string
  name: string
  description: string | null
  optional: boolean
  system: boolean
  defaultEmailEnabled: boolean
  defaultPushEnabled: boolean
  hidden: boolean
  displayOrder: number
}

export interface MatrixPreference {
  channel: string
  type: string
  optedOut: boolean
}

const props = withDefaults(defineProps<{
  types: MatrixNotificationType[]
  preferences: MatrixPreference[]
  channels?: string[]
  accent?: string
  disabled?: boolean
}>(), {
  channels: () => ['EMAIL', 'PUSH'],
  accent: '#2272f2',
  disabled: false,
})

const emit = defineEmits<{ toggle: [channel: string, type: string, optedOut: boolean] }>()

const channelLabels: Record<string, string> = {
  IN_APP: 'In app',
  EMAIL: 'Email',
  PUSH: 'Push',
  WEBHOOK: 'Webhook',
  SLACK: 'Slack',
}

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'type', label: 'Notification', width: 'minmax(220px, 1fr)' },
  ...props.channels.map(c => ({ key: `channel-${c}`, label: channelLabels[c] ?? c, width: '110px' })),
])

const rows = computed(() => props.types.filter(type => !type.hidden).map(type => ({ ...type })))

function isReceiving(type: MatrixNotificationType, channel: string): boolean {
  const pref = props.preferences.find(p => p.type === type.key && p.channel === channel)
  if (pref) return !pref.optedOut
  if (channel === 'EMAIL') return type.defaultEmailEnabled
  if (channel === 'PUSH') return type.defaultPushEnabled
  return true
}

function onToggle(type: MatrixNotificationType, channel: string, receiving: boolean) {
  if (!type.optional || props.disabled) return
  // The switch expresses "receiving"; the API stores the inverse.
  emit('toggle', channel, type.key, !receiving)
}
</script>

<template>
  <GlassTable
    :columns="columns"
    :rows="rows"
    row-key="key"
    empty-text="No notification types defined."
  >
    <template #col-type="{ row }">
      <div class="type-cell">
        <span class="type-name">{{ row.name }}</span>
        <span v-if="row.description" class="type-desc">{{ row.description }}</span>
      </div>
    </template>
    <template v-for="channel in channels" :key="channel" #[`col-channel-${channel}`]="{ row }">
      <Badge v-if="!row.optional" color="var(--fg-3)" :title="'Always delivered — this notification cannot be turned off.'">
        Always on
      </Badge>
      <Switch
        v-else
        :model-value="isReceiving(row, channel)"
        :accent="accent"
        @update:model-value="onToggle(row, channel, $event)"
      />
    </template>
  </GlassTable>
</template>

<style scoped>
.type-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.type-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.type-desc {
  font-size: 11.5px;
  color: var(--fg-3);
}
</style>
