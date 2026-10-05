<script setup lang="ts">
import { AccountLinkRequiredError, BoscaAuthError, useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

const { auth } = useAuth()
const route = useRoute()
const returnTo = computed(() => {
  const value = route.query.returnTo
  return typeof value === 'string' && value.startsWith('/auth/gateway?') ? value : '/'
})
const loginPath = computed(() => withReturnTo('/auth/login'))
const accent = '#7c5cff'
const firstName = ref('')
const lastName = ref('')
const email = ref('')
const password = ref('')
const agreed = ref(false)
const { termsUrl, privacyUrl } = useRuntimeConfig().public
const legalLinks = [
  { label: 'Terms', url: termsUrl },
  { label: 'Privacy Policy', url: privacyUrl },
].filter(link => !!link.url)
const legalSummary = legalLinks.map(link => link.label).join(' and ')
const state = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
const emailError = ref<string | null>(null)
const error = ref<string | null>(null)

function withReturnTo(path: string): string {
  if (returnTo.value === '/') return path
  const separator = path.includes('?') ? '&' : '?'
  return `${path}${separator}returnTo=${encodeURIComponent(returnTo.value)}`
}

const strengthBars = computed(() => {
  const len = password.value.length
  if (len === 0) return 0
  let score = 0
  if (len >= 8) score++
  if (len >= 12) score++
  if (/\d/.test(password.value)) score++
  if (/[^a-zA-Z0-9]/.test(password.value)) score++
  return score
})

const strengthColor = computed(() => {
  if (strengthBars.value <= 1) return '#ff5470'
  if (strengthBars.value <= 2) return accent
  return '#34d99a'
})

const strengthLabel = computed(() => {
  if (password.value.length === 0) return ''
  const labels = ['Weak', 'Fair', 'Good', 'Strong']
  return `${labels[strengthBars.value - 1] || 'Weak'} · ${strengthBars.value} of 4 checks passed`
})

async function handleSignUp() {
  emailError.value = null
  error.value = null
  if (!email.value) {
    emailError.value = 'Enter a valid email address.'
    state.value = 'idle'
    return
  }
  if (legalLinks.length > 0 && !agreed.value) {
    error.value = `You must agree to the ${legalSummary}.`
    state.value = 'error'
    return
  }
  state.value = 'loading'
  try {
    const fullName = `${firstName.value} ${lastName.value}`.trim()
    // Emails are case-insensitive: normalize once so the login identifier, the
    // stored email attribute, and the post-signup sign-in all agree.
    const normalizedEmail = email.value.trim().toLocaleLowerCase()
    const principal = await auth.signUp({
      identifier: normalizedEmail,
      password: password.value,
      // Identify Studio as the originating client; stamped on the new credential.
      originator: 'studio',
      profile: {
        name: fullName,
        visibility: "USER",
        // Persist the name and email as profile attributes (the shape the rest
        // of the platform reads — UI, messaging, git-commit authorship). The
        // backend stores the email as a login credential, not profile data, so
        // the client must attach `bosca.profiles.email` here.
        attributes: [
          {
            typeId: 'bosca.profiles.name',
            attributes: { name: fullName },
            source: 'signup',
            priority: 1,
            confidence: 100,
            visibility: 'USER',
          },
          {
            typeId: 'bosca.profiles.email',
            attributes: { email: normalizedEmail },
            source: 'signup',
            priority: 1,
            confidence: 100,
            visibility: 'USER',
          },
        ],
      },
    })
    state.value = 'success'
    if (!principal.verified) {
      const verifyPath = withReturnTo(`/auth/verify?email=${encodeURIComponent(normalizedEmail)}`)
      await navigateTo(verifyPath, { replace: true, external: true })
      return
    }
    // Password sign-up returns only the new principal — it never mints a
    // session, even when the account is immediately verified (the backend's
    // `security.autoVerify`). Without a session the full-page navigation to `/`
    // hits the Nitro auth gate with no valid token and bounces straight to
    // `/auth/login?unauthorized=true`. Establish the session with the
    // just-entered credentials first, then enter the app.
    await auth.signInWithPassword(normalizedEmail, password.value, 'studio')
    await navigateTo(returnTo.value, { replace: true, external: true })
  } catch (e) {
    // The email already belongs to an existing verified account — route into the proof/linking
    // challenge instead of failing (the token carries the pending link).
    if (e instanceof AccountLinkRequiredError) {
      // Carry the available proof methods so the confirm screen hides proofs that
      // would only fail (e.g. password for an OAuth-only existing account).
      const methodsParam = e.methods.length ? `&methods=${encodeURIComponent(e.methods.join(','))}` : ''
      await navigateTo(`/auth/link/confirm?token=${encodeURIComponent(e.token)}${methodsParam}`, { replace: true })
      return
    }
    state.value = 'error'
    error.value = toSignUpErrorMessage(e)
  }
}

/**
 * Maps a sign-up failure to user-facing copy. Recognized auth errors get a
 * specific, friendly message; everything else falls back to a generic line so a
 * raw backend exception (e.g. a Postgres unique-constraint violation on the
 * identifier) is never shown to the user.
 */
function toSignUpErrorMessage(e: unknown): string {
  if (e instanceof BoscaAuthError) {
    switch (e.code) {
      case 'auth/email-already-registered':
        return 'An account with that email already exists. Try signing in instead.'
      case 'auth/network-error':
        return "We couldn't reach the server. Check your connection and try again."
    }
  }
  return 'Something went wrong creating your account. Please try again.'
}
</script>

<template>
  <AuthFormCard :width="400">
    <AuthHeading
      eyebrow="Create an account"
      title="Start your Bosca Studio."
      :accent="accent"
    />

    <AuthBanner v-if="state === 'error'" tone="err">{{ error }}</AuthBanner>
    <AuthBanner v-if="state === 'success'" tone="ok">Workspace created. Sending you to onboarding…</AuthBanner>

    <AuthProviderRow :return-to="returnTo" />
    <AuthDivider>or with email</AuthDivider>

    <div class="name-grid">
      <AuthInput
        v-model="firstName"
        label="First"
        placeholder="Erin"
        :accent="accent" />
      <AuthInput
        v-model="lastName"
        label="Last"
        placeholder="Maeda"
        :accent="accent" />
    </div>

    <AuthInput
      v-model="email"
      label="Email"
      type="email"
      placeholder="you@company.com"
      :accent="accent"
      :error="emailError"
    />

    <div class="password-section">
      <AuthInput
        v-model="password"
        label="Password"
        type="password"
        placeholder="At least 12 characters"
        :accent="accent"
      />
      <div class="strength-meter">
        <span
          v-for="i in 4"
          :key="i"
          class="strength-bar"
          :style="{ background: i <= strengthBars ? strengthColor : 'var(--bg-3)' }"
        />
      </div>
      <span v-if="strengthLabel" class="strength-label">{{ strengthLabel }}</span>
    </div>

    <label v-if="legalLinks.length > 0" class="terms-check">
      <input v-model="agreed" type="checkbox" :style="{ accentColor: accent }" >
      <span>I agree to the <template v-for="(link, index) in legalLinks" :key="link.label"><template v-if="index > 0"> and </template><a
        :href="link.url"
        target="_blank"
        rel="noopener"
        :style="{ color: accent }"
      >{{ link.label }}</a></template>.</span>
    </label>

    <AuthBtn
      primary
      full
      :accent="accent"
      :loading="state === 'loading'"
      :icon="state === 'loading' ? undefined : 'arrowRight'"
      @click="handleSignUp"
    >
      {{ state === 'loading' ? 'Creating an account' : 'Create account' }}
    </AuthBtn>

    <p class="auth-footer">Already have an account? <NuxtLink :style="{ color: accent }" :to="loginPath">Sign in</NuxtLink></p>
  </AuthFormCard>
</template>

<style scoped>
.name-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }

.password-section { display: flex; flex-direction: column; gap: 6px; }
.strength-meter { display: flex; gap: 4px; margin-top: 2px; }
.strength-bar { flex: 1; height: 3px; border-radius: 2px; transition: background 0.2s; }
.strength-label { font-size: 11px; color: var(--fg-3); }

.terms-check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  color: var(--fg-2);
}
.terms-check input { margin-top: 2px; }
.terms-check a { text-decoration: none; }

.auth-footer {
  margin: 0;
  font-size: 12.5px;
  color: var(--fg-3);
  text-align: center;
  line-height: 1.55;
}
.auth-footer a { text-decoration: none; }
</style>
