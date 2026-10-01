<script setup lang="ts">
import { BoscaForm } from '@bosca/forms'

/**
 * The "learn more" contact form, authored in Bosca and rendered here by key.
 * Capability chips that have no marketing page of their own open this instead
 * of navigating. BoscaForm owns its own loading, validation, success, and
 * error states, so this component is just the frame around it.
 */
const FORM_KEY = 'bosca-io-learn-more'

withDefaults(defineProps<{
  accent?: string
}>(), {
  accent: '#00dc82'
})

const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Modal
    title="Learn more"
    subtitle="Tell us what you're working on and we'll follow up."
    icon="mail"
    :accent="accent"
    width="560px"
    @close="emit('close')"
  >
    <ClientOnly>
      <BoscaForm
        :schema-key="FORM_KEY"
        mode="submit"
        submit-label="Send"
      />
    </ClientOnly>
  </Modal>
</template>
