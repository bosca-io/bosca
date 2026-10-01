<script setup lang="ts">
import { BoscaAuthError, EmailNotVerifiedError, PrincipalNotVerifiedError, useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

const { isAuthenticated, auth } = useAuth()
const route = useRoute()
const returnTo = computed(() => {
  const value = route.query.returnTo
  return typeof value === 'string' && value.startsWith('/auth/gateway?') ? value : '/'
})
const signupPath = computed(() => withReturnTo('/auth/signup'))
const wasRejected = computed(() => route.query.unauthorized !== undefined)
// Set by the email-verification handoff (`/auth/login?accountVerified=true`) so the
// "Email verified" confirmation persists here until the user signs in.
const justVerified = computed(() => route.query.accountVerified !== undefined)
if (import.meta.client && isAuthenticated.value && !wasRejected.value) {
  navigateTo(returnTo.value, { replace: true, external: true })
}

// An OAuth sign-in whose verified email already belongs to an existing account
// is bounced back here with `?link=<token>` instead of completing. Route the user
// into the proof/linking challenge.
onMounted(() => {
  const link = auth.getLinkFromUrl()
  if (link) {
    // Forward the proof methods the backend offered alongside the token, so the
    // confirm screen only shows proofs that can actually succeed (an OAuth-only
    // account has no password to confirm with).
    const methodsParam = link.methods?.length ? `&methods=${encodeURIComponent(link.methods.join(','))}` : ''
    void navigateTo(`/auth/link/confirm?token=${encodeURIComponent(link.token)}${methodsParam}`, { replace: true })
  }
})

const accent = '#7c5cff'
const email = ref('')
const password = ref('')
const state = ref<'idle' | 'loading' | 'error' | 'success'>('idle')
const error = ref<string | null>(null)

function withReturnTo(path: string): string {
  if (returnTo.value === '/') return path
  const separator = path.includes('?') ? '&' : '?'
  return `${path}${separator}returnTo=${encodeURIComponent(returnTo.value)}`
}

async function handleSignIn() {
  error.value = null
  if (!email.value) {
    error.value = 'Enter a valid email address.'
    return
  }
  state.value = 'loading'
  try {
    const { $auth } = useNuxtApp()
    await $auth.signInWithPassword(email.value, password.value, 'studio')
    state.value = 'success'
    if (!$auth.currentUser?.verified) {
      const verifyPath = withReturnTo(`/auth/verify?email=${encodeURIComponent(email.value)}`)
      await navigateTo(verifyPath, { replace: true, external: true })
    } else {
      await navigateTo(returnTo.value, { replace: true, external: true })
    }
  } catch (e) {
    // An unverified account can't sign in — send them to verification (where
    // they can resend the link) rather than showing a dead-end error. Both the
    // account-level gate (PrincipalNotVerifiedError) and an unverified email
    // (EmailNotVerifiedError) resolve the same way: prove the email.
    if (e instanceof PrincipalNotVerifiedError || e instanceof EmailNotVerifiedError) {
      const verifyPath = withReturnTo(`/auth/verify?email=${encodeURIComponent(email.value)}`)
      await navigateTo(verifyPath, { replace: true, external: true })
      return
    }
    state.value = 'error'
    error.value = toSignInErrorMessage(e)
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
        return "We couldn't reach the server. Check your connection and try again."
    }
  }
  return 'Something went wrong while signing you in. Please try again.'
}
</script>

<template>
  <AuthFormCard>
    <AuthHeading
      eyebrow="Sign in"
      title="Welcome back."
      sub="Use your email or a connected provider."
      :accent="accent"
    />

    <AuthBanner v-if="justVerified && state === 'idle'" tone="ok">Email verified. Sign in to continue.</AuthBanner>
    <AuthBanner v-if="wasRejected && state === 'idle'" tone="warn">You're not authorized to view that page. Your session may have expired, or your account may not have the required access (for example, editor permissions). Sign in below with an authorized account to continue.</AuthBanner>
    <AuthBanner v-if="state === 'error'" tone="err">{{ error ?? 'Authentication failed. Try again.' }}</AuthBanner>
    <AuthBanner v-if="state === 'success'" tone="ok">Signed in. Redirecting to…</AuthBanner>

    <AuthProviderRow :return-to="returnTo" />

    <AuthDivider>or with email</AuthDivider>

    <form @submit.prevent="handleSignIn">
      <AuthInput
        v-model="email"
        label="Email"
        placeholder="you@company.com"
        :accent="accent"
        :error="error"
      />

      <div class="password-section">
        <div class="password-header">
          <span class="password-label">Password</span>
        </div>
        <AuthInput
          v-model="password"
          type="password"
          placeholder="••••••••"
          :accent="accent"
        />
      </div>

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
      New here? <NuxtLink :style="{ color: accent }" :to="signupPath">Create an account</NuxtLink> or
      <NuxtLink to="/auth/forgot" class="forgot-link" :style="{ color: accent }">Forgot Password?</NuxtLink>
    </p>
  </AuthFormCard>
</template>

<style scoped>
form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.password-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.password-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.password-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-2);
}
.forgot-link {
  font-size: 11.5px;
  text-decoration: none;
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
