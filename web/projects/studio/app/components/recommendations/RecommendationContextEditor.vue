<script setup lang="ts">
import type { RecommendationContextFormValues } from '~/utils/recommendationContextForm'
import { createRecommendationWeights, describeTypePreference, influenceWeightFields, similarityWeightFields } from '~/utils/recommendationWeights'

const model = defineModel<RecommendationContextFormValues>({ required: true })

withDefaults(defineProps<{
  section?: 'all' | 'General' | 'Eligibility' | 'Weights'
  typeDisabled?: boolean
  saving?: boolean
  error?: string
  savedNote?: string
  submitLabel?: string
  cancelLabel?: string
}>(), {
  section: 'all',
  typeDisabled: false,
  saving: false,
  error: '',
  savedNote: '',
  submitLabel: 'Save changes',
  cancelLabel: 'Cancel',
})

defineEmits<{
  submit: []
  cancel: []
}>()
</script>

<template>
  <div class="editor-stack">
    <p v-if="section === 'all' || section === 'Weights'" class="field-hint">
      Save your changes, then use Train model to start training with the saved settings.
      Changed weights apply when the trained version becomes active. Check Models for its status and captured settings.
    </p>
    <SectionCard
      v-if="section === 'all' || section === 'General'"
      title="Context"
      subtitle="The request-facing context and where it is used"
      padded>
      <div class="form-stack">
        <div class="form-grid">
          <TextInput
            v-model="model.name"
            label="Name"
            placeholder="e.g. Image picker" />
          <TextInput
            v-model="model.type"
            label="Type"
            placeholder="image_picker"
            mono
            :disabled="typeDisabled" />
        </div>
        <p class="field-hint">
          Requests select this context by type. Use lowercase letters, numbers, hyphens, or underscores.
          The default context type cannot be changed.
        </p>
        <Textarea
          v-model="model.description"
          label="Description"
          :rows="2"
          placeholder="Where this recommendation context is used" />
      </div>
    </SectionCard>

    <SectionCard
      v-if="section === 'all' || section === 'Eligibility'"
      title="Metadata"
      subtitle="Which metadata records are candidates in this context"
      padded>
      <div class="filter-stack">
        <div class="filter-pair">
          <TagInput
            v-model="model.metadata.includedContentTypePrefixes"
            label="Included content-type prefixes"
            placeholder="e.g. image/ — press Enter to add" />
          <TagInput
            v-model="model.metadata.excludedContentTypePrefixes"
            label="Excluded content-type prefixes"
            placeholder="e.g. application/octet-stream" />
        </div>
        <p class="field-hint">
          Content types use prefix matching. If any included prefixes are set, they define the complete
          allow-list and the exclusions do not apply.
        </p>
        <div class="filter-pair">
          <TagInput
            v-model="model.metadata.includedAttributeTypes"
            label="Included attributes.type values"
            placeholder="e.g. article" />
          <TagInput
            v-model="model.metadata.excludedAttributeTypes"
            label="Excluded attributes.type values"
            placeholder="e.g. thumbnail" />
        </div>
        <p class="field-hint">
          Attribute types use exact matching. An include-list takes precedence over its exclude-list.
          Empty facets match all values.
        </p>
      </div>
    </SectionCard>

    <SectionCard
      v-if="section === 'all' || section === 'Eligibility'"
      title="Collections"
      subtitle="Which collection records are candidates in this context"
      padded>
      <div class="filter-stack">
        <Switch
          v-model="model.collectionsEnabled"
          label="Collections are eligible in this context" />
        <template v-if="model.collectionsEnabled">
          <div class="filter-pair">
            <TagInput
              v-model="model.collections.includedTypes"
              label="Included collection types"
              placeholder="e.g. STANDARD" />
            <TagInput
              v-model="model.collections.excludedTypes"
              label="Excluded collection types"
              placeholder="e.g. FOLDER" />
          </div>
          <p class="field-hint">
            Collection types use exact matching. If any included types are set, they define the complete
            allow-list and the exclusions do not apply.
          </p>
          <div class="filter-pair">
            <TagInput
              v-model="model.collections.includedAttributeTypes"
              label="Included attributes.type values"
              placeholder="e.g. series" />
            <TagInput
              v-model="model.collections.excludedAttributeTypes"
              label="Excluded attributes.type values"
              placeholder="e.g. archive" />
          </div>
          <p class="field-hint">
            Attribute types use exact matching. An include-list takes precedence over its exclude-list.
            Empty facets match all values.
          </p>
        </template>
        <p v-else class="disabled-note">
          Collections will not be assigned to this context.
        </p>
      </div>
    </SectionCard>

    <SectionCard
      v-if="section === 'all' || section === 'Weights'"
      title="Similarity weights"
      subtitle="How content signals contribute to relatedness"
      padded>
      <div class="form-stack">
        <div class="form-grid">
          <NumberInput
            v-for="field in similarityWeightFields"
            :key="field.key"
            :model-value="model.weights.similarity[field.key]"
            :label="field.label"
            :min="0"
            :max="1"
            :step="0.05"
            @update:model-value="model.weights.similarity[field.key] = $event ?? Number.NaN" />
        </div>
        <p class="field-hint">
          Weights range from 0 to 1 and are relative to the other similarity signals.
          Shared collections means direct membership in the same collection.
          Match source editorial type rewards candidates with the same attributes.type as the source item.
          Raising it favors articles from an article, or studies from a study. It does not increase every type preference.
          MIME compares formats such as text/plain.
        </p>
      </div>
    </SectionCard>

    <SectionCard
      v-if="section === 'all' || section === 'Weights'"
      title="Editorial type preferences"
      subtitle="Favor a candidate's type regardless of the source item's type"
      padded>
      <div class="form-stack">
        <NumberInput
          :model-value="model.weights.defaultTypePreference"
          label="Unlisted or missing type"
          :min="0"
          :max="1"
          :step="0.05"
          @update:model-value="model.weights.defaultTypePreference = $event ?? Number.NaN" />
        <div v-for="(preference, index) in model.weights.typePreferences" :key="index" class="preference-entry">
          <div class="preference-row">
            <TextInput v-model="preference.type" label="Editorial type" placeholder="e.g. study" />
            <NumberInput
              :model-value="preference.weight"
              label="Preference"
              :min="0"
              :max="1"
              :step="0.05"
              @update:model-value="preference.weight = $event ?? Number.NaN" />
            <Button
              icon="trash"
              :title="`Remove ${preference.type || 'type'} preference`"
              :aria-label="`Remove ${preference.type || 'type'} preference`"
              @click="model.weights.typePreferences.splice(index, 1)" />
          </div>
          <p class="field-hint preference-effect">{{ describeTypePreference(preference.weight, model.weights.defaultTypePreference) }}</p>
        </div>
        <Button @click="model.weights.typePreferences.push({ type: '', weight: 0.5 })">Add type preference</Button>
        <p class="field-hint">
          Types match attributes.type, ignoring capitalization and surrounding spaces. A preference of 0.8
          does not mean 80% of recommendations. With unlisted types at 0.5, it gives a 20% higher content score
          at equal relatedness. Other similarity signals and learned behavior can outweigh that advantage.
        </p>
        <p class="field-hint">
          To favor Study, add study here and raise its preference or lower the unlisted preference.
          The strongest difference is 1 versus 0, which doubles the content score at equal relatedness.
          Zero keeps content eligible; use Eligibility to exclude types. A preference cannot include content
          excluded by eligibility or the request's language.
        </p>
      </div>
    </SectionCard>

    <SectionCard
      v-if="section === 'all' || section === 'Weights'"
      title="Recommendation influences"
      subtitle="The importance of content and behavioral evidence"
      padded>
      <div class="form-stack">
        <div class="form-grid">
          <NumberInput
            v-for="field in influenceWeightFields"
            :key="field.key"
            :model-value="model.weights[field.key]"
            :label="field.label"
            :min="0"
            :max="1"
            :step="0.05"
            @update:model-value="model.weights[field.key] = $event ?? Number.NaN" />
        </div>
        <Button @click="model.weights = createRecommendationWeights()">Reset weights to defaults</Button>
      </div>
    </SectionCard>

    <p v-if="error" class="form-error">{{ error }}</p>
    <div class="save-row">
      <span v-if="savedNote" class="saved-note">{{ savedNote }}</span>
      <Button @click="$emit('cancel')">{{ cancelLabel }}</Button>
      <Button
        primary
        :disabled="saving"
        @click="$emit('submit')">{{ saving ? 'Saving…' : submitLabel }}</Button>
    </div>
  </div>
</template>

<style scoped>
.editor-stack,
.form-stack,
.filter-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-grid,
.filter-pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.preference-entry {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.preference-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: end;
  gap: 14px;
}

.field-hint,
.disabled-note,
.form-error,
.saved-note {
  margin: 0;
  font-size: 12px;
}

.field-hint,
.disabled-note,
.saved-note {
  color: var(--fg-3);
  line-height: 1.5;
}

.disabled-note {
  padding: 12px;
  border: 1px dashed var(--line-2);
  border-radius: var(--r-sm);
}

.form-error {
  color: var(--err, #ff5c5c);
}

.save-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.saved-note {
  margin-right: auto;
}

@media (max-width: 760px) {
  .form-grid,
  .preference-row,
  .filter-pair {
    grid-template-columns: 1fr;
  }
}
</style>
