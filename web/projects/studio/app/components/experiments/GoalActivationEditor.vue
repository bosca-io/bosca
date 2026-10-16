<script setup lang="ts">
import ActivationEventEditor from './ActivationEventEditor.vue'
import type { GoalActivation, GoalOutcomeScope } from '~/utils/goalActivation'
import { newGoalActivation, newActivationFilter, newOutcomeScope, splitIdentifiers } from '~/utils/goalActivation'
const activation = defineModel<GoalActivation | null>('activation', { required: true })
const outcomeScope = defineModel<GoalOutcomeScope | null>('outcomeScope', { required: true })
const props = defineProps<{ variations: { key: string; name: string }[] }>()
function setMode(key: string, value: string) {
  if (!activation.value) return
  const rules = activation.value.variationRules.filter(r => r.variationKey !== key)
  if (value !== '__default__') rules.push({ variationKey: key, mode: value as 'EVENT' | 'INHERIT' | 'NOT_APPLICABLE', filter: value === 'EVENT' ? newActivationFilter() : null })
  activation.value.variationRules = rules
}
</script>
<template>
  <section class="activation-editor">
    <h3>Goal activation</h3>
    <p>Experiment activation defines eligibility. Goal activation describes who reached a condition after assignment; it keeps everyone in the overall result.</p>
    <label class="check"><input type="checkbox" :checked="activation !== null" @change="activation = ($event.target as HTMLInputElement).checked ? newGoalActivation() : null">Configure goal activation</label>
    <template v-if="activation">
      <label>Application IDs<input :value="activation.applicationIds.join(', ')" placeholder="Use the experiment activation application" @input="activation.applicationIds = splitIdentifiers(($event.target as HTMLInputElement).value)"></label>
      <label class="check"><input v-model="activation.sameSession" type="checkbox">Same session as experiment activation</label>
      <label>Maximum delay (seconds)<input
        type="number"
        min="1"
        :value="activation.maxDelaySeconds"
        @input="activation.maxDelaySeconds = Number(($event.target as HTMLInputElement).value) || null"></label>
      <label class="check"><input type="checkbox" :checked="activation.defaultFilter !== null" @change="activation.defaultFilter = ($event.target as HTMLInputElement).checked ? newActivationFilter() : null">Default activation event</label>
      <ActivationEventEditor v-if="activation.defaultFilter" v-model="activation.defaultFilter" />
      <fieldset v-for="variation in props.variations" :key="variation.key">
        <legend>{{ variation.name }}</legend>
        <label>Activation mode<select :value="activation.variationRules.find(r => r.variationKey === variation.key)?.mode ?? '__default__'" @change="setMode(variation.key, ($event.target as HTMLSelectElement).value)">
          <option value="__default__">Use default if configured</option><option value="EVENT">Event override</option><option value="INHERIT">Inherit default event</option><option value="NOT_APPLICABLE">Not applicable</option>
        </select></label>
        <template v-for="rule in activation.variationRules.filter(r => r.variationKey === variation.key)" :key="rule.variationKey">
          <ActivationEventEditor v-if="rule.filter" v-model="rule.filter" />
        </template>
      </fieldset>
    </template>
    <h3>Outcome scope</h3>
    <label class="check"><input type="checkbox" :checked="outcomeScope !== null" @change="outcomeScope = ($event.target as HTMLInputElement).checked ? newOutcomeScope() : null">Configure outcome scope and attribution</label>
    <template v-if="outcomeScope">
      <label>Application IDs<input :value="outcomeScope.applicationIds.join(', ')" placeholder="Use the experiment activation application" @input="outcomeScope.applicationIds = splitIdentifiers(($event.target as HTMLInputElement).value)"></label>
      <label class="check"><input v-model="outcomeScope.sameSession" type="checkbox">Same browser session</label>
      <label>Maximum delay (seconds)<input
        type="number"
        min="1"
        :value="outcomeScope.maxDelaySeconds"
        @input="outcomeScope.maxDelaySeconds = Number(($event.target as HTMLInputElement).value) || null"></label>
      <label class="check"><input v-model="outcomeScope.recommendationCard" type="checkbox">Only recommendation-card interactions</label>
      <label class="check"><input v-model="outcomeScope.attributeRecommendationDestination" type="checkbox">Measure verified recommendation destinations</label>
      <p>Verified destinations match the selected content route in the same session and application within five minutes.</p>
    </template>
  </section>
</template>
<style scoped>
.activation-editor { display: grid; gap: 12px; border-top: 1px solid var(--border); padding-top: 16px; }
h3, p { margin: 0; }
p { color: var(--fg-3); font-size: 12px; line-height: 1.5; }
label { display: flex; flex-direction: column; gap: 5px; font-size: 12px; }
.check { flex-direction: row; align-items: center; }
input, select { padding: 8px; background: var(--bg-2); color: var(--fg-1); border: 1px solid var(--border); border-radius: 6px; }
fieldset { display: grid; gap: 12px; padding: 12px; border: 1px solid var(--border); border-radius: 8px; }
</style>
