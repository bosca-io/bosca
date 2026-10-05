<script setup lang="ts">
import gql from 'graphql-tag'

const props = defineProps<{
  metadataId: string
  metadataVersion: number
  existingLanguages: string[]
  accent?: string
}>()

const emit = defineEmits<{
  close: []
  created: [id: string]
}>()

const { mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const addVariantGql = gql`
  mutation AddLanguageVariant($id: UUID!, $version: Int!, $languageTag: String!) {
    content { metadata { addLanguageVariant(id: $id, version: $version, languageTag: $languageTag, setReady: true) { id } } }
  }
`

const languageTag = ref('')
const loading = ref(false)

const commonLanguages = [
  { code: 'en', label: 'English' },
  { code: 'es', label: 'Spanish' },
  { code: 'fr', label: 'French' },
  { code: 'de', label: 'German' },
  { code: 'pt', label: 'Portuguese' },
  { code: 'it', label: 'Italian' },
  { code: 'ja', label: 'Japanese' },
  { code: 'ko', label: 'Korean' },
  { code: 'zh', label: 'Chinese' },
  { code: 'ar', label: 'Arabic' },
  { code: 'ru', label: 'Russian' },
  { code: 'hi', label: 'Hindi' },
  { code: 'nl', label: 'Dutch' },
  { code: 'sv', label: 'Swedish' },
  { code: 'pl', label: 'Polish' },
]

const availableLanguages = computed(() =>
  commonLanguages.filter(l => !props.existingLanguages.includes(l.code)),
)

const canSubmit = computed(() => languageTag.value.trim().length >= 2 && !loading.value)

async function onSubmit() {
  if (!canSubmit.value) return
  loading.value = true
  try {
    const result = await gqlMutation(addVariantGql, {
      id: props.metadataId,
      version: props.metadataVersion,
      languageTag: languageTag.value.trim(),
    })
    const newId = (result as { content?: { metadata?: { addLanguageVariant?: { id: string } } } })?.content?.metadata?.addLanguageVariant?.id
    toast.success(`Language variant "${languageTag.value}" created`)
    emit('created', newId ?? '')
  } catch {
    toast.error('Failed to create language variant')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Modal
    title="Add Language Variant"
    icon="globe"
    :accent="accent"
    @close="emit('close')">
    <div class="variant-form">
      <div class="quick-pick">
        <span class="quick-pick-label">Quick select:</span>
        <div class="quick-pick-list">
          <button
            v-for="lang in availableLanguages"
            :key="lang.code"
            class="lang-chip"
            :class="{ active: languageTag === lang.code }"
            @click="languageTag = lang.code"
          >
            {{ lang.label }} ({{ lang.code }})
          </button>
        </div>
      </div>

      <TextInput v-model="languageTag" label="Language Tag" placeholder="e.g. en, es, fr-CA" />

      <div v-if="existingLanguages.length" class="existing-note">
        Existing: {{ existingLanguages.join(', ') }}
      </div>
    </div>

    <template #footer>
      <span style="flex: 1" />
      <Button size="sm" @click="emit('close')">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!canSubmit"
        @click="onSubmit">
        {{ loading ? 'Creating…' : 'Create Variant' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.variant-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.quick-pick-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
  margin-bottom: 6px;
  display: block;
}

.quick-pick-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.lang-chip {
  padding: 4px 8px;
  border-radius: var(--r-sm);
  font-size: 12px;
  color: var(--fg-2);
  background: var(--bg-2);
  border: 1px solid var(--line);
  transition: all 0.15s;
}

.lang-chip:hover {
  border-color: var(--fg-4);
  color: var(--fg-0);
}

.lang-chip.active {
  background: color-mix(in oklch, var(--brand-2) 14%, transparent);
  border-color: var(--brand-2);
  color: var(--brand-2);
  font-weight: 600;
}

.existing-note {
  font-size: 11.5px;
  color: var(--fg-3);
}
</style>
