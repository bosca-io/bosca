<script setup lang="ts">
import { AccountLinkRequiredError, useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

const { auth } = useAuth()
const route = useRoute()
const accent = '#7c5cff'

const email = computed(() => (route.query.email as string) || '')
const tokenFromUrl = computed(() => (route.query.token as string) || '')

const state = ref<'idle' | 'verifying' | 'success' | 'invalid'>('idle')
const error = ref<string | null>(null)
const resendCountdown = ref(0)
const resendState = ref<'idle' | 'sending' | 'sent' | 'error'>('idle')
const resendError = ref<string | null>(null)
let resendTimer: ReturnType<typeof setInterval> | undefined

async function verifyWithToken(token: string) {
  state.value = 'verifying'
  error.value = null
  try {
    await auth.verifyEmail(token)
    state.value = 'success'
    // Carry a success flag to the login page so the confirmation persists there —
    // right where the user's next action (signing in) is — instead of flashing by
    // on this page as it unmounts.
    await navigateTo('/auth/login?accountVerified=true')
  } catch (e) {
    // The proven email already belongs to an existing verified account — not a dead-end. Route into the
    // proof/linking challenge (the token carries the pending link; proving the existing account attaches this
    // sign-in method to it and retires this duplicate), mirroring the sign-up collision path in signup.vue.
    if (e instanceof AccountLinkRequiredError) {
      // Carry the available proof methods so the confirm screen hides proofs that
      // would only fail (e.g. password for an OAuth-only existing account).
      const methodsParam = e.methods.length ? `&methods=${encodeURIComponent(e.methods.join(','))}` : ''
      await navigateTo(`/auth/link/confirm?token=${encodeURIComponent(e.token)}${methodsParam}`, { replace: true })
      return
    }
    state.value = 'invalid'
    error.value = e instanceof Error ? e.message : 'Verification link is invalid or expired.'
  }
}

async function handleResend() {
  if (resendCountdown.value > 0 || resendState.value === 'sending') return
  if (!email.value) {
    resendError.value = 'Missing email address. Return to sign up to request a new link.'
    resendState.value = 'error'
    return
  }
  resendState.value = 'sending'
  resendError.value = null
  try {
    await auth.resendVerification(email.value)
    resendState.value = 'sent'
  } catch (e) {
    resendState.value = 'error'
    resendError.value = e instanceof Error ? e.message : 'Failed to resend verification email.'
    return
  }
  resendCountdown.value = 30
  resendTimer = setInterval(() => {
    resendCountdown.value--
    if (resendCountdown.value <= 0 && resendTimer) {
      clearInterval(resendTimer)
      resendTimer = undefined
    }
  }, 1000)
}

onMounted(() => {
  if (tokenFromUrl.value) {
    void verifyWithToken(tokenFromUrl.value)
  }
})

onUnmounted(() => {
  if (resendTimer) clearInterval(resendTimer)
})
</script>

<template>
  <AuthFormCard>
    <div
      class="verify-icon"
      :style="{
        background: `color-mix(in oklch, ${accent} 18%, transparent)`,
        border: `1px solid color-mix(in oklch, ${accent} 35%, transparent)`,
      }">
      <Icon name="mail" :size="24" :color="accent" />
    </div>

    <AuthHeading
      eyebrow="Verify your email"
      title="Check your inbox."
      :accent="accent"
    >
      <p v-if="email" class="verify-sub">
        We sent a verification link to <span class="mono" style="color: var(--fg-1)">{{ email }}</span>. Open it on this device to confirm your account.
      </p>
      <p v-else class="verify-sub">
        We sent a verification link to the email you signed up with. Open it on this device to confirm your account.
      </p>
    </AuthHeading>

    <AuthBanner v-if="state === 'verifying'" tone="info">Verifying your email…</AuthBanner>
    <AuthBanner v-if="state === 'success'" tone="ok">Email verified. Redirecting you to sign in…</AuthBanner>
    <AuthBanner v-if="state === 'invalid'" tone="err">{{ error ?? 'Verification link is invalid or expired.' }}</AuthBanner>
    <AuthBanner v-if="resendState === 'sent'" tone="ok">Verification email sent. Check your inbox.</AuthBanner>
    <AuthBanner v-if="resendState === 'error'" tone="err">{{ resendError }}</AuthBanner>

    <AuthBtn
      primary
      full
      :accent="accent"
      :loading="resendState === 'sending'"
      :disabled="resendCountdown > 0 || !email"
      :icon="resendState === 'sending' ? undefined : 'mail'"
      @click="handleResend"
    >
      <template v-if="resendState === 'sending'">Sending</template>
      <template v-else-if="resendCountdown > 0">Resend in {{ resendCountdown }}s</template>
      <template v-else>Resend verification email</template>
    </AuthBtn>

    <p class="auth-footer">
      Wrong email? <NuxtLink :style="{ color: accent }" to="/auth/signup">Start over</NuxtLink>
      · Already verified? <NuxtLink :style="{ color: accent }" to="/auth/login">Sign in</NuxtLink>
    </p>
  </AuthFormCard>
</template>

<style scoped>
.verify-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  align-self: flex-start;
}
.verify-sub {
  margin: 0;
  font-size: 13.5px;
  color: var(--fg-3);
  line-height: 1.5;
}
.auth-footer {
  margin: 0;
  font-size: 12.5px;
  color: var(--fg-3);
  text-align: center;
  line-height: 1.55;
}
.auth-footer a { text-decoration: none; }
</style>
