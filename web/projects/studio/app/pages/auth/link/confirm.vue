<script setup lang="ts">
import type { LinkProofMethod } from '@bosca/auth-client-browser'
import { useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

const { auth } = useAuth()
const { setFlash } = useFlash()
const route = useRoute()
const accent = '#7c5cff'

// `?token=` — the pending-link token from an OAuth sign-in that collided with an
// existing verified account (the proof challenge). `?proof=` — the one-time token
// from the emailed magic-link (proof of email ownership; auto-confirmed on load).
const linkToken = computed(() => (route.query.token as string) || '')
const emailProofToken = computed(() => (route.query.proof as string) || '')

// `?methods=` — the proof methods the backend offered for the existing account.
// An OAuth-only account has no password, so its challenge is `EMAIL` only;
// confirming with a password there always fails with "password proof is not
// available for this account", so we must not show the password field for it.
const proofMethods = computed<LinkProofMethod[]>(() => {
  const raw = route.query.methods
  const value = Array.isArray(raw) ? raw.join(',') : (raw as string) || ''
  return value
    .split(',')
    .map((m) => m.trim())
    .filter((m): m is LinkProofMethod => m === 'PASSWORD' || m === 'EMAIL')
})
// Show the password field only when the account actually has a password. When the
// methods are unknown (e.g. an older link with no `?methods=`), fall back to
// allowing it so a password user is never blocked — email proof is always offered.
const allowPassword = computed(() => proofMethods.value.length === 0 || proofMethods.value.includes('PASSWORD'))

const password = ref('')
const state = ref<'idle' | 'confirming' | 'emailSent' | 'success' | 'error'>('idle')
const error = ref<string | null>(null)

async function completeAndRedirect(run: () => Promise<unknown>) {
  state.value = 'confirming'
  error.value = null
  try {
    await run()
    state.value = 'success'
    // The redirect into the app is a hard reload, so the in-memory success banner
    // here is never seen. Carry the confirmation across the reload as a flash toast.
    setFlash('Account linked — you’re signed in.')
    await navigateTo('/', { replace: true, external: true })
  } catch (e) {
    state.value = 'error'
    error.value = e instanceof Error ? e.message : 'Something went wrong. Please try again.'
  }
}

function confirmWithPassword() {
  if (!linkToken.value) {
    state.value = 'error'
    error.value = 'This request has expired. Please start over.'
    return
  }
  if (!password.value) {
    error.value = 'Enter your password to continue.'
    return
  }
  void completeAndRedirect(() => auth.linkConfirmPassword(linkToken.value, password.value))
}

async function requestEmailProof() {
  if (!linkToken.value) {
    state.value = 'error'
    error.value = 'This request has expired. Please start over.'
    return
  }
  state.value = 'confirming'
  error.value = null
  try {
    await auth.linkRequestEmailProof(linkToken.value)
    state.value = 'emailSent'
  } catch (e) {
    state.value = 'error'
    error.value = e instanceof Error ? e.message : 'Could not send the email. Please try again.'
  }
}

onMounted(() => {
  if (emailProofToken.value) {
    void completeAndRedirect(() => auth.linkConfirmEmail(emailProofToken.value))
  }
})
</script>

<template>
  <AuthFormCard>
    <AuthHeading
      eyebrow="Account already exists"
      title="Confirm it's you."
      :accent="accent"
    >
      <p v-if="emailProofToken" class="link-sub">
        Confirming your account link…
      </p>
      <p v-else class="link-sub">
        An account with this email already exists. Prove it's yours to connect this new sign-in method.
      </p>
    </AuthHeading>

    <AuthBanner v-if="state === 'confirming'" tone="info">Working on it…</AuthBanner>
    <AuthBanner v-if="state === 'success'" tone="ok">Done. Signing you in…</AuthBanner>
    <AuthBanner v-if="state === 'emailSent'" tone="ok">Check your inbox — we sent a link to confirm. Open it on this device to finish.</AuthBanner>
    <AuthBanner v-if="state === 'error'" tone="err">{{ error ?? 'Something went wrong. Please try again.' }}</AuthBanner>

    <!-- Proof options. Hidden in the emailed-link (?proof) flow, which auto-confirms. -->
    <template v-if="!emailProofToken && state !== 'success' && state !== 'emailSent'">
      <!-- Password proof + email fallback, when the existing account has a password. -->
      <template v-if="allowPassword">
        <form @submit.prevent="confirmWithPassword">
          <AuthInput
            v-model="password"
            type="password"
            label="Password"
            placeholder="••••••••"
            :accent="accent"
            :error="error"
          />
          <AuthBtn
            primary
            full
            :accent="accent"
            :loading="state === 'confirming'"
            :icon="state === 'confirming' ? undefined : 'arrowRight'"
            type="submit"
          >
            {{ state === 'confirming' ? 'Confirming' : 'Confirm with password' }}
          </AuthBtn>
        </form>

        <AuthDivider>no password?</AuthDivider>

        <AuthBtn
          full
          :accent="accent"
          :disabled="state === 'confirming'"
          icon="mail"
          @click="requestEmailProof"
        >
          Email me a link instead
        </AuthBtn>
      </template>

      <!-- OAuth-only account: no password exists, so email is the only proof path. -->
      <template v-else>
        <p class="link-sub">
          This account signs in with a connected provider, so we'll email a one-time link to confirm it's you.
        </p>
        <AuthBtn
          primary
          full
          :accent="accent"
          :loading="state === 'confirming'"
          :icon="state === 'confirming' ? undefined : 'mail'"
          @click="requestEmailProof"
        >
          {{ state === 'confirming' ? 'Sending' : 'Email me a link to confirm' }}
        </AuthBtn>
      </template>
    </template>

    <p class="auth-footer">
      Not you? <NuxtLink :style="{ color: accent }" to="/auth/login">Back to sign in</NuxtLink>
    </p>
  </AuthFormCard>
</template>

<style scoped>
form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.link-sub {
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
.auth-footer a {
  text-decoration: none;
}
</style>
