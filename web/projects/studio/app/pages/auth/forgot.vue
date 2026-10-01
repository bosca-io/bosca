<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

const { auth } = useAuth()
const accent = '#7c5cff'
const email = ref('')
const state = ref<'idle' | 'loading' | 'sent'>('idle')

async function handleSend() {
  if (!email.value) return
  state.value = 'loading'
  try {
    await auth.forgotPassword(email.value)
  } catch {
    // Always show "sent" to avoid leaking whether the email exists
  }
  state.value = 'sent'
}
</script>

<template>
  <AuthFormCard>
    <NuxtLink to="/auth/login" class="back-link">
      <Icon name="arrowLeft" :size="12" color="var(--fg-3)" />
      Back to sign in
    </NuxtLink>

    <AuthHeading
      eyebrow="Reset password"
      title="Forgot your password?"
      sub="We'll email you a link to set a new one."
      :accent="accent"
    />

    <AuthBanner v-if="state === 'sent'" tone="ok">If an account exists for that email, a reset link is on its way.</AuthBanner>

    <AuthInput
      v-model="email"
      label="Email"
      type="email"
      placeholder="you@company.com"
      :accent="accent"
    />

    <AuthBtn
      primary
      full
      :accent="accent"
      :loading="state === 'loading'"
      :icon="state === 'loading' ? undefined : 'mail'"
      @click="handleSend"
    >
      {{ state === 'loading' ? 'Sending link' : 'Send reset link' }}
    </AuthBtn>
  </AuthFormCard>
</template>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
  text-decoration: none;
  align-self: flex-start;
}
</style>
