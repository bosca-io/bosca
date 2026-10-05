<script setup lang="ts">
import {
  BoscaAuthError,
  EmailNotVerifiedError,
  PrincipalNotVerifiedError,
  useAuth
} from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })
useSeoMeta({
  title: 'Sign in',
  description: 'Sign in to the Bosca platform.',
  // Keep the optional sign-in form out of the search index.
  robots: 'noindex, nofollow'
})

const { isAuthenticated, auth } = useAuth()
const route = useRoute()
const wasRejected = computed(() => route.query.unauthorized !== undefined)
// Set by signup (`/auth/login?verify=<email>`) when the new account still
// needs email verification before it can sign in.
const pendingEmail = typeof route.query.verify === 'string' ? route.query.verify : null

if (import.meta.client && isAuthenticated.value && !wasRejected.value) {
  navigateTo('/developers/getting-started', { replace: true, external: true })
}

// An OAuth sign-in whose verified email already belongs to an existing account
// is bounced back here with `?link=<token>`. The docs site doesn't host the
// proof/linking flow, so point the user at their existing credentials instead.
const linkRequired = ref(false)
onMounted(() => {
  if (auth.getLinkFromUrl()) {
    linkRequired.value = true
  }
})

const accent = '#7c5cff'
const email = ref(pendingEmail ?? '')
const password = ref('')
const state = ref<'idle' | 'loading' | 'error' | 'success'>('idle')
const error = ref<string | null>(null)
const needsVerification = ref(pendingEmail !== null)
const resendState = ref<'idle' | 'sending' | 'sent'>('idle')

async function handleSignIn() {
  error.value = null
  if (!email.value) {
    error.value = 'Enter a valid email address.'
    return
  }
  state.value = 'loading'
  try {
    const { $auth } = useNuxtApp()
    await $auth.signInWithPassword(email.value.trim().toLocaleLowerCase(), password.value, 'docs')
    state.value = 'success'
    await navigateTo('/developers/getting-started', { replace: true, external: true })
  } catch (e) {
    // An unverified account can't sign in — offer to resend the verification
    // email rather than showing a dead-end error.
    if (e instanceof PrincipalNotVerifiedError || e instanceof EmailNotVerifiedError) {
      needsVerification.value = true
      resendState.value = 'idle'
      state.value = 'idle'
      return
    }
    state.value = 'error'
    error.value = toSignInErrorMessage(e)
  }
}

async function handleResend() {
  if (resendState.value === 'sending') return
  resendState.value = 'sending'
  error.value = null
  try {
    await auth.resendVerification(email.value.trim().toLocaleLowerCase())
    resendState.value = 'sent'
  } catch {
    resendState.value = 'idle'
    state.value = 'error'
    error.value = 'We couldn\'t resend the verification email. Please try again.'
  }
}

/**
 * Maps a sign-in failure to user-facing copy. Recognized auth errors get a
 * specific, friendly message; everything else falls back to a generic line so a
 * raw backend/GraphQL exception (e.g. "Exception while fetching data … : missing
 * credentials") is never shown to the user.
 */
function toSignInErrorMessage(e: unknown): string {
  if (e instanceof BoscaAuthError) {
    switch (e.code) {
      case 'auth/invalid-credentials':
        return 'The email or password you entered is incorrect.'
      case 'auth/network-error':
        return 'We couldn\'t reach the server. Check your connection and try again.'
    }
  }
  return 'Something went wrong while signing you in. Please try again.'
}
</script>

<template>
  <AuthFormCard>
    <AuthHeading
      eyebrow="Sign in"
      title="Welcome to the docs."
      sub="Sign in with your Bosca account to start reading."
      :accent="accent"
    />

    <AuthBanner
      v-if="wasRejected && state === 'idle' && !needsVerification"
      tone="warn"
    >
      Your session may have expired, or your account may not have the required access. Sign in below to continue.
    </AuthBanner>
    <AuthBanner
      v-if="linkRequired"
      tone="info"
    >
      That email is already registered with a password. Sign in with your email and password to continue.
    </AuthBanner>
    <AuthBanner
      v-if="needsVerification"
      tone="ok"
    >
      <template v-if="resendState === 'sent'">
        Verification email sent. Check your inbox, then sign in.
      </template>
      <template v-else>
        Your email needs to be verified before you can sign in. Check your inbox, or <button
          class="resend-link"
          @click="handleResend"
        >
          {{ resendState === 'sending' ? 'sending…' : 'resend the email' }}
        </button>.
      </template>
    </AuthBanner>
    <AuthBanner
      v-if="state === 'error'"
      tone="err"
    >
      {{ error ?? 'Authentication failed. Try again.' }}
    </AuthBanner>
    <AuthBanner
      v-if="state === 'success'"
      tone="ok"
    >
      Signed in. Redirecting…
    </AuthBanner>

    <AuthProviderRow />

    <AuthDivider>or with email</AuthDivider>

    <form @submit.prevent="handleSignIn">
      <AuthInput
        v-model="email"
        label="Email"
        placeholder="you@company.com"
        :accent="accent"
        :error="error"
      />

      <AuthInput
        v-model="password"
        label="Password"
        type="password"
        placeholder="••••••••"
        :accent="accent"
      />

      <AuthBtn
        primary
        full
        :accent="accent"
        :loading="state === 'loading'"
        :icon="state === 'loading' ? undefined : 'arrowRight'"
        type="submit"
      >
        {{ state === 'loading' ? 'Signing in' : 'Sign in' }}
      </AuthBtn>
    </form>

    <p class="auth-footer">
      New here? <NuxtLink
        :style="{ color: accent }"
        to="/auth/signup"
      >Create an account</NuxtLink>
    </p>
  </AuthFormCard>
</template>

<style scoped>
form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.resend-link {
  background: none;
  border: none;
  padding: 0;
  font: inherit;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
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
