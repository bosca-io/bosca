<script setup lang="ts">
import gql from 'graphql-tag'

interface BibleVariant {
  variant: string
  enabled: boolean
  defaultVariant: boolean
  name: string
  nameLocal: string
  abbreviation: string
  abbreviationLocal: string
}

const props = defineProps<{
  metadataId: string
  metadataVersion: number
  accent?: string
}>()

const emit = defineEmits<{
  close: []
  updated: []
}>()

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const variantsGql = gql`
  query ManageBibleVariants($id: UUID!, $version: Int!) {
    content {
      metadata(id: $id, version: $version) {
        bibles(includeDisabled: true) {
          variant
          enabled
          defaultVariant
          name
          nameLocal
          abbreviation
          abbreviationLocal
        }
      }
    }
  }
`

const setEnabledGql = gql`
  mutation SetBibleVariantEnabled($id: UUID!, $version: Int!, $variant: String!, $enabled: Boolean!) {
    content {
      metadata {
        setMetadataBibleVariantEnabled(id: $id, version: $version, variant: $variant, enabled: $enabled)
      }
    }
  }
`

const setDefaultGql = gql`
  mutation SetDefaultBibleVariant($id: UUID!, $version: Int!, $variant: String!) {
    content {
      metadata {
        setMetadataBibleDefaultVariant(id: $id, version: $version, variant: $variant)
      }
    }
  }
`

const variants = ref<BibleVariant[]>([])
const loading = ref(true)
const error = ref('')
const savingVariant = ref<string | null>(null)

function displayName(variant: BibleVariant): string {
  return variant.nameLocal || variant.name || variant.variant
}

function abbreviation(variant: BibleVariant): string {
  return variant.abbreviationLocal || variant.abbreviation || variant.variant
}

async function loadVariants(showLoading = true) {
  if (showLoading) loading.value = true
  error.value = ''
  try {
    const result = await gqlQuery<{
      content: {
        metadata: {
          bibles: BibleVariant[]
        } | null
      }
    }>(variantsGql, { id: props.metadataId, version: props.metadataVersion })
    variants.value = result.content.metadata?.bibles ?? []
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load Bible variants'
  } finally {
    if (showLoading) loading.value = false
  }
}

async function setEnabled(variant: BibleVariant, enabled: boolean) {
  if (savingVariant.value || variant.enabled === enabled || (variant.defaultVariant && !enabled)) return
  savingVariant.value = variant.variant
  try {
    await gqlMutation(setEnabledGql, {
      id: props.metadataId,
      version: props.metadataVersion,
      variant: variant.variant,
      enabled,
    })
    await loadVariants(false)
    emit('updated')
    toast.success(`${displayName(variant)} ${enabled ? 'enabled' : 'disabled'}`)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update Bible variant')
  } finally {
    savingVariant.value = null
  }
}

async function setDefault(variant: BibleVariant) {
  if (savingVariant.value || variant.defaultVariant || !variant.enabled) return
  savingVariant.value = variant.variant
  try {
    await gqlMutation(setDefaultGql, {
      id: props.metadataId,
      version: props.metadataVersion,
      variant: variant.variant,
    })
    await loadVariants(false)
    emit('updated')
    toast.success(`${displayName(variant)} is now the default`)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update the default Bible variant')
  } finally {
    savingVariant.value = null
  }
}

onMounted(() => loadVariants())
</script>

<template>
  <Modal
    title="Bible Variants"
    subtitle="Choose the default edition and which editions are available"
    icon="book"
    :accent="accent"
    width="620px"
    @close="emit('close')"
  >
    <div v-if="loading" class="variant-state">Loading variants…</div>

    <div v-else-if="error" class="variant-state error-state">
      <span>{{ error }}</span>
      <Button size="sm" @click="loadVariants()">Retry</Button>
    </div>

    <div v-else-if="variants.length === 0" class="variant-state">
      No Bible variants were found for this metadata version.
    </div>

    <div v-else class="variant-list">
      <div class="variant-head" aria-hidden="true">
        <span>Edition</span>
        <span>Default</span>
        <span>Enabled</span>
      </div>
      <div
        v-for="variant in variants"
        :key="variant.variant"
        class="variant-row"
        :class="{ unavailable: !variant.enabled }"
      >
        <div class="variant-identity">
          <span class="variant-name">{{ displayName(variant) }}</span>
          <span class="variant-detail">
            <span class="mono">{{ abbreviation(variant) }}</span>
            <span class="separator">·</span>
            <span class="mono">{{ variant.variant }}</span>
          </span>
        </div>

        <label class="default-control">
          <input
            type="radio"
            name="default-bible-variant"
            :checked="variant.defaultVariant"
            :disabled="!variant.enabled || savingVariant !== null"
            :aria-label="`Make ${displayName(variant)} the default variant`"
            @change="setDefault(variant)"
          >
          <span>{{ variant.defaultVariant ? 'Default' : 'Set default' }}</span>
        </label>

        <div class="enabled-control">
          <Switch
            :model-value="variant.enabled"
            :disabled="variant.defaultVariant || savingVariant !== null"
            :aria-label="`${variant.enabled ? 'Disable' : 'Enable'} ${displayName(variant)}`"
            :accent="accent"
            @update:model-value="setEnabled(variant, $event)"
          />
          <span v-if="savingVariant === variant.variant" class="saving-label">Saving…</span>
        </div>
      </div>
      <p class="variant-hint">The default edition is always enabled. Disabled editions are hidden from ordinary Bible queries.</p>
    </div>

    <template #footer>
      <span class="footer-spacer" />
      <Button size="sm" @click="emit('close')">Close</Button>
    </template>
  </Modal>
</template>

<style scoped>
.variant-state {
  min-height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: var(--fg-3);
  font-size: 13px;
  text-align: center;
}

.error-state {
  color: var(--danger, #ff5d6c);
}

.variant-list {
  display: flex;
  flex-direction: column;
}

.variant-head,
.variant-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 130px 92px;
  gap: 16px;
  align-items: center;
}

.variant-head {
  padding: 0 12px 8px;
  color: var(--fg-4);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.variant-row {
  min-height: 68px;
  padding: 10px 12px;
  border-top: 1px solid var(--line);
  transition: opacity 0.15s;
}

.variant-row.unavailable {
  opacity: 0.58;
}

.variant-identity {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.variant-name {
  overflow: hidden;
  color: var(--fg-1);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.variant-detail {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--fg-3);
  font-size: 11px;
}

.separator {
  color: var(--fg-4);
}

.default-control {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--fg-2);
  font-size: 12px;
  cursor: pointer;
}

.default-control:has(input:disabled) {
  cursor: not-allowed;
}

.default-control input {
  accent-color: var(--brand-accent);
}

.enabled-control {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
}

.saving-label {
  color: var(--fg-4);
  font-size: 10px;
}

.variant-hint {
  margin: 12px 4px 0;
  color: var(--fg-3);
  font-size: 11.5px;
}

.footer-spacer {
  flex: 1;
}

@media (max-width: 560px) {
  .variant-head {
    display: none;
  }

  .variant-row {
    grid-template-columns: 1fr auto;
  }

  .variant-identity {
    grid-column: 1 / -1;
  }
}
</style>
