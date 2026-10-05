<script setup lang="ts">
import gql from 'graphql-tag'

withDefaults(defineProps<{
  accent?: string
}>(), { accent: '#38bdf8' })

const emit = defineEmits<{
  close: []
  created: [id: string]
}>()

const { mutation: gqlMutation } = useGraphQL()

const name = ref('')
const channelType = ref('PUBLIC')
const creating = ref(false)
const error = ref('')

const typeOptions = [
  { value: 'PUBLIC', label: 'Public' },
  { value: 'GROUP', label: 'Group' },
]

const createChannelGql = gql`
  mutation CreateChannel($name: String!, $type: ChatChannelType!, $attributes: JSON) {
    chat {
      createChannel(name: $name, type: $type, attributes: $attributes) {
        id
        name
        type
      }
    }
  }
`

const canCreate = computed(() => name.value.trim().length > 0 && !creating.value)

async function onCreate() {
  if (!canCreate.value) return
  creating.value = true
  error.value = ''
  try {
    const result = await gqlMutation<{
      chat: { createChannel: { id: string; name: string; type: string } }
    }>(createChannelGql, {
      name: name.value.trim(),
      type: channelType.value,
    })
    const channelId = result.chat.createChannel.id
    emit('created', channelId)
    emit('close')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create channel'
  } finally {
    creating.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
  } else if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    onCreate()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Modal
    title="New Channel"
    subtitle="Create a chat channel for your team"
    icon="message"
    :accent="accent"
    @close="emit('close')"
  >
    <TextInput
      v-model="name"
      label="Channel name"
      placeholder="e.g. design-reviews"
      autofocus
    />

    <Select
      v-model="channelType"
      label="Type"
      :options="typeOptions"
      :accent="accent"
    />

    <div v-if="error" class="error-msg">{{ error }}</div>

    <template #footer>
      <span class="hint mono">⌘ Enter</span>
      <span class="spacer" />
      <Button size="sm" @click="emit('close')">Cancel</Button>
      <Button
        primary
        size="sm"
        :accent="accent"
        :disabled="!canCreate"
        @click="onCreate">
        {{ creating ? 'Creating...' : 'Create Channel' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.error-msg {
  font-size: 12px;
  color: var(--err);
  padding: 6px 10px;
  background: color-mix(in oklch, var(--err) 10%, transparent);
  border: 1px solid color-mix(in oklch, var(--err) 25%, transparent);
  border-radius: var(--r-sm);
}

.hint {
  font-size: 10.5px;
  color: var(--fg-4);
}

.spacer {
  flex: 1;
}
</style>
