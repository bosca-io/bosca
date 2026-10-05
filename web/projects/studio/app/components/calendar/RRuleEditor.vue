<script lang="ts" setup>
import { parseRRule, buildRRule, WEEKDAYS, type RRuleConfig, type RRuleFreq, type Weekday } from '~/utils/rrule'

const props = defineProps<{
  modelValue: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
}>()

const enabled = ref(!!props.modelValue)
const advancedMode = ref(false)
const advancedText = ref(props.modelValue ?? '')

const config = ref<RRuleConfig>(
  props.modelValue ? parseRRule(props.modelValue) : {
    freq: 'WEEKLY',
    interval: 1,
    byDay: [],
    count: null,
    until: null,
  },
)

const endMode = ref<'never' | 'count' | 'until'>(
  config.value.count != null ? 'count' : config.value.until != null ? 'until' : 'never',
)

const untilText = ref(
  config.value.until
    ? config.value.until.toISOString().slice(0, 10)
    : '',
)

watch(() => props.modelValue, (v) => {
  if (v) {
    enabled.value = true
    config.value = parseRRule(v)
    advancedText.value = v
    endMode.value = config.value.count != null ? 'count' : config.value.until != null ? 'until' : 'never'
    untilText.value = config.value.until ? config.value.until.toISOString().slice(0, 10) : ''
  } else {
    enabled.value = false
  }
})

function emitUpdate() {
  if (!enabled.value) {
    emit('update:modelValue', null)
    return
  }
  if (advancedMode.value) {
    emit('update:modelValue', advancedText.value || null)
    return
  }

  const c = { ...config.value }
  if (endMode.value === 'never') {
    c.count = null
    c.until = null
  } else if (endMode.value === 'count') {
    c.until = null
  } else if (endMode.value === 'until') {
    c.count = null
    if (untilText.value) {
      c.until = new Date(untilText.value + 'T23:59:59Z')
    }
  }

  emit('update:modelValue', buildRRule(c))
}

function toggleEnabled(checked: boolean) {
  enabled.value = checked
  emitUpdate()
}

function toggleDay(day: Weekday) {
  const idx = config.value.byDay.indexOf(day)
  if (idx >= 0) config.value.byDay.splice(idx, 1)
  else config.value.byDay.push(day)
  emitUpdate()
}

const FREQ_OPTIONS: { value: RRuleFreq; label: string }[] = [
  { value: 'DAILY', label: 'Daily' },
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'YEARLY', label: 'Yearly' },
]

const DAY_LABELS: Record<string, string> = {
  MO: 'M', TU: 'T', WE: 'W', TH: 'T', FR: 'F', SA: 'S', SU: 'S',
}
</script>

<template>
  <div class="rrule-editor">
    <div class="rrule-toggle">
      <Switch :model-value="enabled" @update:model-value="toggleEnabled" />
      <span class="rrule-toggle-label">Repeat</span>
    </div>

    <template v-if="enabled">
      <!-- Advanced mode toggle -->
      <div class="rrule-mode">
        <button class="mode-btn" :class="{ 'mode-btn--active': !advancedMode }" @click="advancedMode = false">Simple</button>
        <button class="mode-btn" :class="{ 'mode-btn--active': advancedMode }" @click="advancedMode = true">Advanced</button>
      </div>

      <!-- Advanced: raw RRULE string -->
      <div v-if="advancedMode" class="rrule-advanced">
        <input
          v-model="advancedText"
          class="rrule-input rrule-input--mono"
          placeholder="FREQ=WEEKLY;BYDAY=MO,WE"
          @blur="emitUpdate"
        >
      </div>

      <!-- Simple mode -->
      <template v-else>
        <div class="rrule-row">
          <label class="rrule-label">Every</label>
          <input
            v-model.number="config.interval"
            type="number"
            class="rrule-input rrule-input--sm"
            min="1"
            @change="emitUpdate"
          >
          <select v-model="config.freq" class="rrule-select" @change="emitUpdate">
            <option v-for="opt in FREQ_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>

        <!-- BYDAY for WEEKLY -->
        <div v-if="config.freq === 'WEEKLY'" class="rrule-days">
          <button
            v-for="day in WEEKDAYS"
            :key="day"
            class="day-btn"
            :class="{ 'day-btn--active': config.byDay.includes(day) }"
            @click="toggleDay(day)"
          >
            {{ DAY_LABELS[day] }}
          </button>
        </div>

        <!-- End condition -->
        <div class="rrule-row">
          <label class="rrule-label">Ends</label>
          <select v-model="endMode" class="rrule-select" @change="emitUpdate">
            <option value="never">Never</option>
            <option value="count">After N occurrences</option>
            <option value="until">On date</option>
          </select>
        </div>

        <div v-if="endMode === 'count'" class="rrule-row">
          <label class="rrule-label">After</label>
          <input
            v-model.number="config.count"
            type="number"
            class="rrule-input rrule-input--sm"
            min="1"
            @change="emitUpdate"
          >
          <span class="rrule-suffix">occurrences</span>
        </div>

        <div v-if="endMode === 'until'" class="rrule-row">
          <label class="rrule-label">Until</label>
          <input
            v-model="untilText"
            type="date"
            class="rrule-input"
            @change="emitUpdate"
          >
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.rrule-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rrule-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rrule-toggle-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
}

.rrule-mode {
  display: flex;
  gap: 2px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  padding: 2px;
}

.mode-btn {
  padding: 3px 10px;
  font-size: 11px;
  background: none;
  border: none;
  border-radius: var(--r-xs);
  color: var(--fg-3);
  cursor: pointer;
}

.mode-btn--active {
  background: var(--bg-0);
  color: var(--fg-0);
  font-weight: 500;
}

.rrule-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rrule-label {
  font-size: 12px;
  color: var(--fg-3);
  min-width: 40px;
}

.rrule-suffix {
  font-size: 12px;
  color: var(--fg-3);
}

.rrule-input {
  padding: 5px 8px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  flex: 1;
}

.rrule-input:focus { border-color: var(--brand-2); }
.rrule-input--sm { width: 60px; flex: none; }
.rrule-input--mono { font-family: var(--font-mono, monospace); font-size: 12px; }

.rrule-select {
  padding: 5px 8px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
}

.rrule-select:focus { border-color: var(--brand-2); }

.rrule-days {
  display: flex;
  gap: 4px;
}

.day-btn {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid var(--line);
  background: none;
  color: var(--fg-3);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.day-btn:hover {
  border-color: var(--brand-2);
  color: var(--fg-1);
}

.day-btn--active {
  background: var(--brand-2);
  border-color: var(--brand-2);
  color: #fff;
}

.rrule-advanced {
  display: flex;
}
</style>
