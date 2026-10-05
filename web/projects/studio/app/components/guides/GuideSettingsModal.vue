<script setup lang="ts">
import { GuideType } from '~/types/graphql'

const props = defineProps<{
  /** Current guide type, e.g. 'LINEAR' | 'CALENDAR' | … */
  type: string
  /** Current full rrule (may include DTSTART / DTEND / RRULE lines), or null. */
  rrule?: string | null
  accent?: string
  loading?: boolean
}>()

const emit = defineEmits<{
  close: []
  save: [payload: { type: string; rrule: string | null }]
}>()

const guideTypeOptions = [
  { value: GuideType.Linear, label: 'Linear' },
  { value: GuideType.LinearProgress, label: 'Linear Progress' },
  { value: GuideType.Calendar, label: 'Calendar' },
  { value: GuideType.CalendarProgress, label: 'Calendar Progress' },
]

const localType = ref<string>(props.type || GuideType.Linear)
const isCalendar = computed(() => localType.value === GuideType.Calendar || localType.value === GuideType.CalendarProgress)

// The guide's rrule bundles scheduling anchors (DTSTART/DTEND — owned by the
// separate start-date modal) with the recurrence pattern (RRULE line). The
// RRuleEditor only round-trips the RRULE line, so we split the two apart,
// edit only the RRULE line here, and recombine on save so a recurrence change
// never drops the start date.
const originalLines = computed(() =>
  (props.rrule ?? '').split('\n').map((l) => l.trim()).filter(Boolean))
const anchorLines = computed(() =>
  originalLines.value.filter((l) => l.startsWith('DTSTART') || l.startsWith('DTEND')))
const originalRrulePart = computed(() =>
  originalLines.value.find((l) => l.startsWith('RRULE:')) ?? '')

const rrulePart = ref(originalRrulePart.value)

/** The full rrule to persist, preserving DTSTART/DTEND anchors, or null when not applicable. */
function composedRrule(): string | null {
  if (!isCalendar.value || !rrulePart.value) return null
  return [...anchorLines.value, rrulePart.value].join('\n')
}

function onSave() {
  emit('save', { type: localType.value, rrule: composedRrule() })
}
</script>

<template>
  <Modal
    title="Guide Settings"
    subtitle="Change how users navigate this guide"
    icon="settings"
    :accent="accent"
    width="420px"
    @close="emit('close')"
  >
    <div class="settings-body">
      <Select
        v-model="localType"
        :options="guideTypeOptions"
        label="Guide Type" />

      <div v-if="isCalendar" class="recurrence-section">
        <span class="section-label">Recurrence</span>
        <TemplatesRRuleEditor
          :model-value="rrulePart || undefined"
          @update:model-value="(v: string) => { rrulePart = v }" />
        <p class="recurrence-hint">The start date is set separately from the guide navigation bar.</p>
      </div>
    </div>

    <template #footer>
      <span class="spacer" />
      <Button size="sm" @click="emit('close')">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="loading"
        @click="onSave">
        {{ loading ? 'Saving…' : 'Save' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.settings-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.recurrence-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.recurrence-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  margin: 4px 0 0;
}

.spacer { flex: 1; }
</style>
