<script setup lang="ts">
const props = defineProps<{
  type: 'DATE' | 'DATETIME'
}>()

interface DateParameterValue {
  value?: string | null
  now: boolean
  nowDayOffset?: number | null
}

const model = defineModel<DateParameterValue>({
  default: () => ({ now: false, value: null, nowDayOffset: 0 }),
})

const isNow = computed({
  get: () => model.value?.now || false,
  set: (val) => {
    model.value = { ...model.value, now: val }
  },
})

const dayOffset = computed({
  get: () => model.value?.nowDayOffset || 0,
  set: (val) => {
    model.value = { ...model.value, nowDayOffset: !isNaN(val) ? val : undefined }
  },
})

const dateValue = computed({
  get: () => {
    if (!model.value?.value) return ''
    try {
      const d = new Date(model.value.value)
      if (isNaN(d.getTime())) return ''
      if (props.type === 'DATE') return d.toISOString().split('T')[0]
      const pad = (n: number) => n.toString().padStart(2, '0')
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
    } catch {
      return ''
    }
  },
  set: (val) => {
    if (!val) { model.value = { ...model.value, value: null }; return }
    const d = new Date(val)
    if (!isNaN(d.getTime())) {
      model.value = { ...model.value, value: d.toISOString() }
    }
  },
})

const inputType = computed(() => props.type === 'DATE' ? 'date' : 'datetime-local')
</script>

<template>
  <div class="date-param">
    <label class="date-param__toggle">
      <Checkbox v-model="isNow" />
      <span>Relative to now</span>
    </label>
    <div v-if="isNow" class="date-param__offset">
      <input v-model.number="dayOffset" type="number" class="date-param__input mono" >
      <span class="date-param__hint">day offset (negative for past)</span>
    </div>
    <div v-else>
      <input v-model="dateValue" :type="inputType" class="date-param__input" >
    </div>
  </div>
</template>

<style scoped>
.date-param {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  background: var(--bg-3);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.date-param__toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--fg-1);
  cursor: pointer;
}

.date-param__offset {
  display: flex;
  align-items: center;
  gap: 8px;
}

.date-param__hint {
  font-size: 11px;
  color: var(--fg-3);
}

.date-param__input {
  width: 100%;
  padding: 6px 10px;
  font-size: 12px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  outline: none;
  color-scheme: dark;
}

.date-param__input:focus {
  border-color: var(--brand-2);
}

.date-param__input[type="number"] {
  width: 80px;
}
</style>
