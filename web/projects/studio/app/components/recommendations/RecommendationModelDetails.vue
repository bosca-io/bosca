<script setup lang="ts">
import type { RecommendationContext } from '~/types/graphql'
import { describeTypePreference, influenceWeightFields, similarityWeightFields } from '~/utils/recommendationWeights'

const props = defineProps<{
  version: number | string
  revision: number | string
  context: Pick<RecommendationContext, 'name' | 'type' | 'description' | 'weights' | 'contentFilter'>
}>()
defineEmits<{ close: [] }>()
const filters = computed(() => [
  { label: 'Metadata', value: props.context.contentFilter.metadata, fields: [
    { key: 'includedContentTypePrefixes', label: 'Included MIME prefixes' },
    { key: 'excludedContentTypePrefixes', label: 'Excluded MIME prefixes' },
    { key: 'includedAttributeTypes', label: 'Included attributes.type values' },
    { key: 'excludedAttributeTypes', label: 'Excluded attributes.type values' },
  ] },
  { label: 'Collections', value: props.context.contentFilter.collections, fields: [
    { key: 'includedTypes', label: 'Included collection types' },
    { key: 'excludedTypes', label: 'Excluded collection types' },
    { key: 'includedAttributeTypes', label: 'Included attributes.type values' },
    { key: 'excludedAttributeTypes', label: 'Excluded attributes.type values' },
  ] },
])
function filterValues(value: object, key: string): string {
  const values = (value as Record<string, unknown>)[key]
  return Array.isArray(values) && values.length ? values.join(', ') : 'Not set'
}
</script>

<template>
  <Modal
    :title="`Model ${version} configuration`"
    :subtitle="`Context revision ${revision}, captured when training was queued.`"
    width="min(92vw, 900px)"
    @close="$emit('close')">
    <div class="snapshot">
      <SectionCard title="General" padded>
        <dl><div><dt>Name</dt><dd>{{ context.name }}</dd></div><div><dt>Type</dt><dd>{{ context.type }}</dd></div><div><dt>Description</dt><dd>{{ context.description || 'Not set' }}</dd></div></dl>
      </SectionCard>
      <SectionCard
        v-for="filter in filters"
        :key="filter.label"
        :title="filter.label"
        padded>
        <dl v-if="filter.value">
          <div v-for="field in filter.fields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ filterValues(filter.value, field.key) }}</dd></div>
        </dl>
        <p v-else>Not eligible</p>
      </SectionCard>
      <SectionCard title="Similarity weights" padded>
        <dl><div v-for="field in similarityWeightFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ context.weights.similarity[field.key] }}</dd></div></dl>
      </SectionCard>
      <SectionCard title="Editorial type preferences" padded>
        <dl>
          <div><dt>Unlisted or missing type</dt><dd>{{ context.weights.defaultTypePreference }}</dd></div>
          <div v-for="preference in context.weights.typePreferences" :key="preference.type">
            <dt>{{ preference.type }}</dt>
            <dd>{{ preference.weight }}<small>{{ describeTypePreference(preference.weight, context.weights.defaultTypePreference) }}</small></dd>
          </div>
        </dl>
      </SectionCard>
      <SectionCard title="Recommendation influences" padded>
        <dl><div v-for="field in influenceWeightFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ context.weights[field.key] }}</dd></div></dl>
      </SectionCard>
    </div>
    <template #footer><Button @click="$emit('close')">Close</Button></template>
  </Modal>
</template>

<style scoped>
.snapshot { display: flex; flex-direction: column; gap: 14px; }
dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin: 0; }
dt { color: var(--fg-3); font-size: 12px; }
dd { margin: 4px 0 0; overflow-wrap: anywhere; }
small { display: block; margin-top: 4px; color: var(--fg-3); line-height: 1.5; }
</style>
