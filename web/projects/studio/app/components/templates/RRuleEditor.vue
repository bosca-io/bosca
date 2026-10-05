<script lang="ts" setup>
const props = defineProps<{
  modelValue?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const config = ref({
  freq: 'DAILY',
  interval: 1,
  byDay: [] as string[],
  count: undefined as number | undefined,
})

function parseRRule(rruleString: string) {
  if (!rruleString) return
  try {
    const parts = rruleString.split('\n')
    const rrulePart = parts.find(p => p.startsWith('RRULE:'))
    if (!rrulePart) return
    const params = rrulePart.substring(6).split(';')
    params.forEach((param) => {
      const [key, value] = param.split('=')
      if (!key || !value) return
      switch (key) {
        case 'FREQ': config.value.freq = value; break
        case 'INTERVAL': config.value.interval = parseInt(value); break
        case 'BYDAY': config.value.byDay = value.split(','); break
        case 'COUNT': config.value.count = parseInt(value); break
      }
    })
  } catch { /* ignore parse errors */ }
}

function generateRRule() {
  const parts: string[] = [`FREQ=${config.value.freq}`, `INTERVAL=${config.value.interval}`]
  if (config.value.byDay.length > 0) parts.push(`BYDAY=${config.value.byDay.join(',')}`)
  if (config.value.count) parts.push(`COUNT=${config.value.count}`)
  return `RRULE:${parts.join(';')}`
}

watch(config, () => {
  if (mode.value === 'custom') emit('update:modelValue', generateRRule())
}, { deep: true })

const mode = ref<'preset' | 'custom'>('preset')
const selectedPresetValue = ref<string | null>(null)

const presetPatterns = [
  { label: 'Every day', value: 'RRULE:FREQ=DAILY;INTERVAL=1' },
  { label: 'Every weekday Mon–Fri', value: 'RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;INTERVAL=1' },
  { label: 'Every week on Mon', value: 'RRULE:FREQ=WEEKLY;BYDAY=MO;INTERVAL=1' },
  { label: 'Every 2 weeks on Mon', value: 'RRULE:FREQ=WEEKLY;BYDAY=MO;INTERVAL=2' },
  { label: 'Every month', value: 'RRULE:FREQ=MONTHLY;INTERVAL=1' },
  { label: 'Every year', value: 'RRULE:FREQ=YEARLY;INTERVAL=1' },
]

function parseRRuleString(rrule: string): Record<string, string> {
  const clean = rrule.replace(/^RRULE:/, '').trim()
  const obj: Record<string, string> = {}
  for (const part of clean.split(';')) {
    const [key, value] = part.split('=')
    if (key && value) obj[key.trim().toUpperCase()] = value.trim().toUpperCase()
  }
  return obj
}

function findMatchingPreset(rrule: string) {
  const rObj = parseRRuleString(rrule)
  return presetPatterns.find((preset) => {
    const pObj = parseRRuleString(preset.value)
    const rKeys = Object.keys(rObj)
    const pKeys = Object.keys(pObj)
    if (rKeys.length !== pKeys.length) return false
    return rKeys.every(key => pObj[key] === rObj[key])
  })
}

function selectPreset(rrule: string) {
  emit('update:modelValue', rrule)
  mode.value = 'preset'
  selectedPresetValue.value = rrule
}

function selectCustom() {
  mode.value = 'custom'
  selectedPresetValue.value = null
  if (props.modelValue) parseRRule(props.modelValue)
}

const dayOptions = [
  { label: 'M', value: 'MO' }, { label: 'T', value: 'TU' },
  { label: 'W', value: 'WE' }, { label: 'T', value: 'TH' },
  { label: 'F', value: 'FR' }, { label: 'S', value: 'SA' },
  { label: 'S', value: 'SU' },
]

const freqOptions = computed(() => {
  const i = config.value.interval || 1
  return [
    { label: i === 1 ? 'day' : 'days', value: 'DAILY' },
    { label: i === 1 ? 'week' : 'weeks', value: 'WEEKLY' },
    { label: i === 1 ? 'month' : 'months', value: 'MONTHLY' },
    { label: i === 1 ? 'year' : 'years', value: 'YEARLY' },
  ]
})

onMounted(() => {
  if (props.modelValue) {
    const preset = findMatchingPreset(props.modelValue)
    if (preset) {
      mode.value = 'preset'
      selectedPresetValue.value = preset.value
    } else {
      mode.value = 'custom'
      parseRRule(props.modelValue)
    }
  }
})
</script>

<template>
  <div class="rrule-editor">
    <template v-if="mode === 'preset'">
      <button
        v-for="preset in presetPatterns"
        :key="preset.label"
        class="preset-btn"
        :class="{ selected: selectedPresetValue === preset.value }"
        :disabled="disabled"
        @click="selectPreset(preset.value)"
      >
        <Icon
          v-if="selectedPresetValue === preset.value"
          name="check"
          :size="14"
          color="var(--fg-0)" />
        <span>{{ preset.label }}</span>
      </button>
      <button class="preset-btn custom-btn" :disabled="disabled" @click="selectCustom">
        Custom…
      </button>
    </template>

    <template v-else>
      <div class="custom-row">
        <span class="custom-label">Every</span>
        <NumberInput v-model="config.interval" :min="1" placeholder="1" />
        <Select v-model="config.freq" :options="freqOptions" />
      </div>

      <div class="days-row">
        <span class="custom-label">On</span>
        <div class="days-grid">
          <button
            v-for="day in dayOptions"
            :key="day.value"
            class="day-btn"
            :class="{ active: config.byDay.includes(day.value) }"
            :disabled="disabled"
            type="button"
            @click="config.byDay.includes(day.value) ? config.byDay.splice(config.byDay.indexOf(day.value), 1) : config.byDay.push(day.value)"
          >
            {{ day.label }}
          </button>
        </div>
      </div>

      <NumberInput
        :model-value="config.count ?? null"
        label="End After (count)"
        :min="1"
        placeholder="No limit"
        @update:model-value="config.count = $event ?? undefined"
      />

      <button class="back-link" :disabled="disabled" @click="mode = 'preset'">
        Back to presets
      </button>
    </template>
  </div>
</template>

<style scoped>
.rrule-editor {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preset-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 8px 12px;
  border-radius: var(--r-sm);
  font-size: 13px;
  color: var(--fg-1);
  transition: background 0.15s;
}

.preset-btn:hover { background: var(--bg-3); }
.preset-btn.selected {
  background: color-mix(in oklch, var(--brand-2) 15%, transparent);
  font-weight: 600;
  color: var(--fg-0);
}

.custom-btn {
  font-weight: 600;
  color: var(--fg-2);
  margin-top: 4px;
}

.custom-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.custom-label {
  font-size: 13px;
  color: var(--fg-2);
  flex-shrink: 0;
}

.days-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.days-grid {
  display: flex;
  gap: 4px;
}

.day-btn {
  width: 30px;
  height: 30px;
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 600;
  background: var(--bg-3);
  color: var(--fg-2);
  transition: background 0.15s, color 0.15s;
}

.day-btn.active {
  background: color-mix(in oklch, var(--brand-2) 40%, transparent);
  color: var(--fg-0);
}

.day-btn:hover:not(.active) { background: var(--bg-2); }

.back-link {
  font-size: 13px;
  color: var(--fg-3);
  text-decoration: underline;
  text-align: left;
  padding: 4px 0;
  margin-top: 8px;
}

.back-link:hover { color: var(--fg-1); }
</style>
