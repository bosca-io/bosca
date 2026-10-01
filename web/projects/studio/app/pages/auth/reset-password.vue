<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

// `useAuth()` wraps Vue's `inject()`, which only resolves during synchronous
// component setup. It must be called here — not inside the async click handler,
// where there is no active component instance and inject would throw.
const { auth } = useAuth()
const route = useRoute()
const accent = '#7c5cff'
const newPassword = ref('')
const confirmPassword = ref('')
const state = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
const confirmError = ref<string | null>(null)
const error = ref<string | null>(null)

const reqs = computed(() => [
  { label: '12+ characters', met: newPassword.value.length >= 12 },
  { label: 'One number', met: /\d/.test(newPassword.value) },
  { label: 'One symbol', met: /[^a-zA-Z0-9]/.test(newPassword.value) },
])

const strengthBars = computed(() => reqs.value.filter(r => r.met).length + (newPassword.value.length >= 8 ? 1 : 0))
const strengthColor = computed(() => {
  if (strengthBars.value <= 1) return '#ff5470'
  if (strengthBars.value <= 3) return accent
  return '#34d99a'
})

async function handleReset() {
  confirmError.value = null
  error.value = null
  if (newPassword.value !== confirmPassword.value) {
    confirmError.value = "Passwords don't match"
    return
  }
  const token = route.query.token as string
  if (!token) {
    error.value = 'Missing reset token. Please request a new reset link.'
    state.value = 'error'
    return
  }
  state.value = 'loading'
  try {
    await auth.resetPassword(token, newPassword.value)
    state.value = 'success'
    await navigateTo('/auth/login')
  } catch (e) {
    state.value = 'error'
    error.value = e instanceof Error ? e.message : 'Password reset failed'
  }
}
</script>

<template>
  <AuthFormCard>
    <AuthHeading
      eyebrow="Reset password"
      title="Set a new password."
      sub="Choose something memorable. We'll sign you in on this device after."
      :accent="accent"
    />

    <AuthBanner v-if="state === 'error'" tone="err">{{ error }}</AuthBanner>
    <AuthBanner v-if="state === 'success'" tone="ok">Password updated. Redirecting to sign in…</AuthBanner>

    <AuthInput
      v-model="newPassword"
      label="New password"
      type="password"
      :accent="accent"
    />

    <AuthInput
      v-model="confirmPassword"
      label="Confirm password"
      type="password"
      :accent="accent"
      :error="confirmError"
    />

    <div class="strength-section">
      <div class="strength-track">
        <div
          v-for="i in 4"
          :key="i"
          class="strength-bar"
          :style="{ background: i <= strengthBars ? strengthColor : 'transparent' }"
        />
      </div>
      <div class="req-row">
        <span
          v-for="(r, i) in reqs"
          :key="i"
          class="req-item"
          :style="{ color: r.met ? '#34d99a' : 'var(--fg-3)' }">
          <span class="req-dot" :class="{ met: r.met }">
            <Icon
              v-if="r.met"
              name="check"
              :size="8"
              color="#34d99a" />
          </span>
          {{ r.label }}
        </span>
      </div>
    </div>

    <AuthBtn
      primary
      full
      :accent="accent"
      :loading="state === 'loading'"
      @click="handleReset"
    >
      {{ state === 'loading' ? 'Updating' : state === 'success' ? 'Updated' : 'Update password' }}
    </AuthBtn>
  </AuthFormCard>
</template>

<style scoped>
.strength-section { display: flex; flex-direction: column; gap: 6px; }
.strength-track {
  display: flex;
  gap: 4px;
  height: 4px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.06);
}
.strength-bar { flex: 1; transition: background 0.2s; }
.req-row { display: flex; gap: 12px; flex-wrap: wrap; }
.req-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
}
.req-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line-2);
  background: transparent;
}
.req-dot.met {
  background: color-mix(in oklch, #34d99a 25%, transparent);
  border: none;
}
</style>
