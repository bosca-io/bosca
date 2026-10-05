<script setup lang="ts">
import gql from 'graphql-tag'
import type { MatrixNotificationType, MatrixPreference } from '~/components/NotificationPreferenceMatrix.vue'

definePageMeta({ layout: false })

const route = useRoute()
const token = computed(() => (route.query.token as string | undefined) ?? '')

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()

const types = ref<MatrixNotificationType[]>([])
const preferences = ref<MatrixPreference[]>([])
const isLoading = ref(true)
const invalidToken = ref(false)
const saving = ref(false)
const message = ref<{ text: string, error: boolean } | null>(null)

const preferencesGql = gql`
  query TokenNotificationPreferences($token: String!) {
    communications {
      notificationTypes { key name description optional system defaultEmailEnabled defaultPushEnabled hidden displayOrder }
      tokenNotificationPreferences(token: $token) { profileId channel type optedOut updatedAt }
    }
  }
`

async function load() {
  if (!token.value) {
    invalidToken.value = true
    isLoading.value = false
    return
  }
  try {
    const data = await gqlQuery(preferencesGql, { token: token.value }) as {
      communications: {
        notificationTypes: MatrixNotificationType[]
        tokenNotificationPreferences: MatrixPreference[] | null
      }
    } | null
    const prefs = data?.communications?.tokenNotificationPreferences ?? null
    if (!prefs) {
      invalidToken.value = true
    } else {
      types.value = data?.communications?.notificationTypes ?? []
      preferences.value = prefs
    }
  } catch {
    invalidToken.value = true
  } finally {
    isLoading.value = false
  }
}

onMounted(load)

async function onToggle(_channel: string, type: string, optedOut: boolean) {
  saving.value = true
  message.value = null
  try {
    await gqlMutation(
      gql`mutation UpdateTokenNotificationPreference($token: String!, $type: String!, $optedOut: Boolean!) {
        communications { updateTokenNotificationPreference(token: $token, type: $type, optedOut: $optedOut) }
      }`,
      { token: token.value, type, optedOut },
    )
    const idx = preferences.value.findIndex(p => p.channel === 'EMAIL' && p.type === type)
    if (idx >= 0) preferences.value[idx] = { ...preferences.value[idx]!, optedOut }
    message.value = { text: 'Your preferences have been updated.', error: false }
  } catch (e: unknown) {
    message.value = { text: e instanceof Error ? e.message : 'Failed to update your preferences.', error: true }
  } finally {
    saving.value = false
  }
}

async function unsubscribeAll() {
  saving.value = true
  message.value = null
  try {
    await gqlMutation(
      gql`mutation Unsubscribe($token: String!) { communications { unsubscribe(token: $token) } }`,
      { token: token.value },
    )
    preferences.value = preferences.value.map(p =>
      p.channel === 'EMAIL' && types.value.find(t => t.key === p.type)?.optional
        ? { ...p, optedOut: true }
        : p,
    )
    message.value = { text: 'You have been unsubscribed from all optional email.', error: false }
  } catch (e: unknown) {
    message.value = { text: e instanceof Error ? e.message : 'Failed to unsubscribe.', error: true }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="unsubscribe-page">
    <div class="panel">
      <h1 class="title">Email preferences</h1>

      <p v-if="isLoading" class="status">Loading…</p>

      <template v-else-if="invalidToken">
        <p class="status">
          This link is invalid or has expired. You can manage your notification
          preferences after signing in.
        </p>
      </template>

      <template v-else>
        <p class="subtitle">
          Choose which email you'd like to receive. Essential account and
          security messages are always delivered.
        </p>

        <NotificationPreferenceMatrix
          :types="types"
          :preferences="preferences"
          :channels="['EMAIL']"
          :disabled="saving"
          @toggle="onToggle"
        />

        <p v-if="message" class="status" :class="{ error: message.error }">{{ message.text }}</p>

        <div class="actions">
          <Button size="sm" :disabled="saving" @click="unsubscribeAll">
            Unsubscribe from all optional email
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.unsubscribe-page {
  min-height: 100vh;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 64px 16px;
  background: var(--bg-0, #0b0d10);
}

.panel {
  width: 100%;
  max-width: 640px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.title {
  font-size: 20px;
  font-weight: 600;
  color: var(--fg-0, #e8eaed);
}

.subtitle {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2, #9aa0a6);
}

.status {
  font-size: 13px;
  color: var(--fg-2, #9aa0a6);
}

.status.error {
  color: #f87171;
}

.actions {
  display: flex;
  justify-content: flex-end;
}
</style>
