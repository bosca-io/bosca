<script setup lang="ts">
import {
  FEED_TYPE_OPTIONS, FEED_OWNERSHIP_OPTIONS, FEED_AUTH_OPTIONS, authSecretLabel,
  type FeedSourceForm,
} from '~/composables/useFeedSourceForm'

const form = defineModel<FeedSourceForm>('form', { required: true })
defineProps<{
  accent: string
  /** Edit mode changes the secret hint (blank keeps the stored secret). */
  edit?: boolean
}>()

const secretLabel = computed(() => authSecretLabel(form.value.authKind))
const showSecret = computed(() => form.value.authKind !== 'none')
</script>

<template>
  <div class="form-stack">
    <TextInput
      v-model="form.name"
      label="Name"
      placeholder="e.g. The Verge — Tech" />
    <Textarea
      v-model="form.description"
      label="Description"
      :rows="2"
      placeholder="What this source publishes (optional)" />

    <div class="form-row">
      <Select
        v-model="form.type"
        label="Type"
        :options="FEED_TYPE_OPTIONS"
        :accent="accent" />
      <Select
        v-model="form.ownership"
        label="Ownership"
        :options="FEED_OWNERSHIP_OPTIONS"
        :accent="accent" />
    </div>

    <TextInput
      v-model="form.endpoint"
      label="Endpoint URL"
      mono
      placeholder="https://example.com/feed.xml" />
    <TextInput
      v-model="form.cronInterval"
      label="Fetch schedule (cron)"
      mono
      placeholder="0 * * * *  — hourly" />

    <Select
      v-model="form.authKind"
      label="Outbound authentication"
      :options="FEED_AUTH_OPTIONS"
      :accent="accent" />

    <TextInput
      v-if="form.authKind === 'basic'"
      v-model="form.authUsername"
      label="Username" />
    <TextInput
      v-if="form.authKind === 'apiKey'"
      v-model="form.authHeader"
      label="Header name"
      mono
      placeholder="X-Api-Key" />
    <template v-if="form.authKind === 'oauth2'">
      <TextInput
        v-model="form.authTokenUrl"
        label="Token URL"
        mono
        placeholder="https://auth.example.com/oauth/token" />
      <div class="form-row">
        <TextInput
          v-model="form.authClientId"
          label="Client ID" />
        <TextInput
          v-model="form.authScope"
          label="Scope (optional)" />
      </div>
    </template>

    <div v-if="showSecret" class="field">
      <TextInput
        v-model="form.authSecret"
        :label="secretLabel"
        :placeholder="edit ? 'Leave blank to keep the stored secret' : ''" />
      <p class="hint">Stored privately in the configuration service — never returned by the API.</p>
    </div>
  </div>
</template>

<style scoped>
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 5px; }
.hint { color: var(--fg-3); font-size: 11.5px; margin: 0; }
</style>
