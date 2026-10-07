<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

const model = defineModel<string>({ default: '' })
const props = defineProps<{
  kind: 'webhook' | 'token'
  options: SelectOption[]
  names: string[]
  disabled: boolean
}>()
const emit = defineEmits<{ saved: [name: string] }>()
const { mutation } = useGraphQL()
const { accent } = useCurrentSubsystem()
const open = ref(false)
const name = ref('')
const value = ref('')
const saving = ref(false)
const error = ref('')
const label = computed(() => props.kind === 'webhook' ? 'Webhook secret name' : 'Token secret name')
const actionLabel = computed(() => `${props.names.includes(model.value) ? 'Replace' : 'Add'} ${props.kind} secret`)
const replacing = computed(() => props.names.includes(name.value.trim()))

function show() {
  name.value = model.value
  value.value = ''
  error.value = ''
  open.value = true
}
function close() {
  if (saving.value) return
  value.value = ''
  name.value = ''
  error.value = ''
  open.value = false
}
async function save() {
  if (saving.value || props.disabled || !name.value.trim() || !value.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const result = await mutation<{ pipelines: { setSecret: { name: string } } }>(gql`
      mutation SetGitHubSyncSecret($name: String!, $value: String!) {
        pipelines { setSecret(name: $name, value: $value) { name } }
      }
    `, { name: name.value.trim(), value: value.value })
    value.value = ''
    model.value = result.pipelines.setSecret.name
    emit('saved', result.pipelines.setSecret.name)
    open.value = false
    name.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not save the secret.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="secret-field">
    <Select
      v-model="model"
      :label="label"
      :options="options"
      :disabled="disabled || saving"
      allow-custom
      searchable />
    <Button
      type="button"
      size="sm"
      :disabled="disabled || saving"
      @click="show">{{ actionLabel }}</Button>
  </div>
  <Modal
    v-if="open"
    :title="actionLabel"
    :accent="accent"
    @close="close">
    <form class="secret-form" @submit.stop.prevent="save">
      <p class="help">{{ kind === 'token' ? 'Enter a GitHub access token that can access this repository.' : 'Enter the shared secret you will also configure on the GitHub webhook.' }} Values are encrypted and cannot be read back.</p>
      <TextInput
        v-model="name"
        label="Secret name"
        :disabled="saving"
        autofocus />
      <TextInput
        v-model="value"
        :label="kind === 'token' ? 'GitHub token' : 'Webhook secret value'"
        type="password"
        :disabled="saving" />
      <p v-if="replacing" class="help">This name already exists. Saving replaces its value everywhere this secret is used.</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <div class="secret-actions">
        <Button type="button" :disabled="saving" @click="close">Cancel</Button>
        <Button
          type="submit"
          primary
          :accent="accent"
          :disabled="saving || !name.trim() || !value.trim()">{{ saving ? 'Saving…' : 'Save secret' }}</Button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.secret-field, .secret-form { display: flex; flex-direction: column; gap: 12px; }
.secret-field > :last-child { align-self: flex-start; }
.secret-actions { display: flex; justify-content: flex-end; gap: 12px; }
.help { color: var(--fg-2); font-size: 13px; line-height: 1.6; margin: 0; }
.error { color: var(--err); margin: 0; }
</style>
