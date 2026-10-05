<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'welcome' })

const authState = import.meta.client ? useAuth() : null
const profile = computed(() => authState?.profile.value ?? null)
const { isAdmin, hasPersonas, ready } = usePersonas()

// Only show the "no access" messaging once access has actually been resolved
// and confirmed empty. Until then (or while an admin/persona-holder is being
// redirected away by the watch below) we render a neutral loader — otherwise
// an admin who momentarily routes through /welcome sees a misleading "you
// don't have access" flash before the client resolves their access.
const resolvedNoAccess = computed(
  () => ready.value && !isAdmin.value && !hasPersonas.value,
)

const firstName = computed(() => {
  const name = profile.value?.name
  if (!name) return null
  return name.trim().split(/\s+/)[0]
})

// Anyone with access — a persona, or an admin (who gets every subsystem) —
// belongs in the app, not here. Routing already keeps them out of /welcome;
// this guards a direct visit (e.g. typing the URL) once access resolves.
watch(
  () => isAdmin.value || hasPersonas.value,
  (hasAccess) => {
    if (!hasAccess) return
    // Back to the home hub, which lets them pick a subsystem (and re-gates to
    // /welcome only if access somehow disappears — so no redirect loop).
    navigateTo('/', { replace: true })
  },
  { immediate: true },
)

async function handleSignOut() {
  await authState?.auth?.signOut()
  await navigateTo('/auth/login')
}

/**
 * Key of the Bosca Forms schema that backs persona access requests. The schema
 * (type SUBMISSION, published + public) is authored in the Form Builder at
 * /forms/builder; submissions land in /forms/submissions for an admin to review
 * and assign a persona.
 */
const PERSONA_REQUEST_FORM_KEY = 'persona-request'

type RequestState = 'loading' | 'available' | 'unavailable'
const requestState = ref<RequestState>('loading')
const requestSubmitted = ref(false)

// The forms SDK is wired in a client-only plugin (02.forms.client.ts), so it is
// only available in the browser. Guard exactly like the useAuth() call above.
let forms: ReturnType<typeof useBoscaForms> | null = null
if (import.meta.client) {
  try {
    forms = useBoscaForms()
  } catch (err) {
    console.error('Bosca Forms not initialized on /welcome:', err)
  }
}

onMounted(async () => {
  // Admins/persona-holders are redirected away; only the no-access audience
  // resolves the request form.
  if (isAdmin.value || hasPersonas.value) return
  if (!forms) {
    requestState.value = 'unavailable'
    return
  }
  try {
    // Resolve the schema up front to decide whether the form is published yet.
    // fetchSchema caches the result, so the BoscaForm below reuses it without a
    // second network request.
    const schema = await forms.fetchSchema(PERSONA_REQUEST_FORM_KEY)
    requestState.value = schema ? 'available' : 'unavailable'
  } catch (err) {
    console.error('Failed to load the persona request form:', err)
    requestState.value = 'unavailable'
  }
})

function onRequestSubmitted() {
  requestSubmitted.value = true
}
</script>

<template>
  <!-- Access not yet resolved (or an admin/persona-holder being redirected
       out): show a neutral loader, never the "no access" card. -->
  <div v-if="!resolvedNoAccess" class="welcome-resolving">Loading…</div>

  <div v-else class="welcome-card">
    <div class="welcome-brand">
      <BoscaMark :size="32" />
      <div class="brand-text">
        <span class="brand-name">Bosca</span>
        <span class="brand-sep" />
        <span class="brand-label">Studio</span>
      </div>
    </div>

    <div class="welcome-body">
      <h1 class="welcome-title">
        {{ firstName ? `Welcome, ${firstName}.` : 'Welcome.' }}
      </h1>
      <p class="welcome-message">
        Your account is set up, but you don't have access to any Studio features yet.
        An administrator needs to assign a persona to your profile before you can get started.
      </p>
    </div>

    <ClientOnly>
      <!-- Submitted: thank-you confirmation -->
      <div v-if="requestSubmitted" class="request-success">
        <div class="request-success-icon">
          <Icon name="check" :size="16" color="var(--brand-accent)" />
        </div>
        <div class="request-success-text">
          <p class="request-success-title">Request submitted</p>
          <p class="request-success-body">
            An administrator will review your request and assign a persona.
            You'll get access as soon as it's approved.
          </p>
        </div>
      </div>

      <!-- Form is published: render it for submission -->
      <div v-else-if="requestState === 'available'" class="request-form">
        <div class="request-header">
          <span class="request-label">Request access</span>
        </div>
        <BoscaForm
          schema-key="persona-request"
          mode="submit"
          submit-label="Submit request"
          @submitted="onRequestSubmitted"
        />
      </div>

      <!-- Form not published yet: informational message, no request control -->
      <div v-else-if="requestState === 'unavailable'" class="welcome-info">
        <div class="info-icon">
          <Icon name="info" :size="14" color="var(--brand-accent)" />
        </div>
        <p class="info-text">
          Personas control which parts of Studio you can use — like Analytics, CMS, or Work Ops.
          Contact your team administrator to request access.
        </p>
      </div>

      <!-- Resolving whether the form is available -->
      <div v-else class="request-loading">Loading…</div>

      <template #fallback>
        <div class="request-loading">Loading…</div>
      </template>
    </ClientOnly>

    <div class="welcome-footer">
      <button class="sign-out-btn" @click="handleSignOut">
        <Icon name="log-out" :size="14" color="var(--fg-3)" />
        Sign out
      </button>
    </div>
  </div>
</template>

<style scoped>
.welcome-resolving {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 540px;
  margin-inline: auto;
  min-height: 200px;
  font-size: 13px;
  color: var(--fg-3);
}

.welcome-card {
  width: 100%;
  max-width: 540px;
  margin-inline: auto;
  background: color-mix(in oklch, var(--bg-0) 40%, transparent);
  backdrop-filter: blur(40px) saturate(1.6);
  -webkit-backdrop-filter: blur(40px) saturate(1.6);
  border: 1px solid color-mix(in oklch, var(--fg-3) 14%, transparent);
  border-radius: var(--r-lg);
  box-shadow:
    0 24px 80px -20px rgba(0, 0, 0, 0.5),
    inset 0 0 0 1px color-mix(in oklch, var(--fg-4) 6%, transparent),
    inset 0 1px 0 0 color-mix(in oklch, #fff 5%, transparent);
  padding: 40px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.welcome-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.brand-sep {
  width: 1px;
  height: 16px;
  background: var(--fg-3);
  align-self: center;
}

.brand-label {
  font-size: 14px;
  color: var(--fg-2);
  font-weight: 500;
}

.welcome-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.welcome-title {
  margin: 0;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: #f3f5fb;
  line-height: 1.15;
}

.welcome-message {
  margin: 0;
  font-size: 14px;
  color: var(--fg-2);
  line-height: 1.6;
}

/* Info (form not published yet) */
.welcome-info {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  background: color-mix(in oklch, var(--brand-accent) 6%, transparent);
  border: 1px solid color-mix(in oklch, var(--brand-accent) 16%, transparent);
  border-radius: 10px;
}

.info-icon {
  flex: 0 0 auto;
  margin-top: 1px;
}

.info-text {
  margin: 0;
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.55;
}

/* Request access form */
.request-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.request-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.request-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.request-help {
  margin: 0;
  font-size: 13px;
  color: var(--fg-3);
  line-height: 1.55;
}

.request-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px;
  font-size: 13px;
  color: var(--fg-3);
}

/* Submission confirmation */
.request-success {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  background: color-mix(in oklch, var(--brand-accent) 6%, transparent);
  border: 1px solid color-mix(in oklch, var(--brand-accent) 16%, transparent);
  border-radius: 10px;
}

.request-success-icon {
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in oklch, var(--brand-accent) 14%, transparent);
  border: 1px solid color-mix(in oklch, var(--brand-accent) 24%, transparent);
}

.request-success-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.request-success-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.request-success-body {
  margin: 0;
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.55;
}

/* Footer */
.welcome-footer {
  display: flex;
  justify-content: flex-start;
  align-items: center;
}

.sign-out-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-3);
  background: transparent;
  border: 1px solid color-mix(in oklch, var(--fg-3) 16%, transparent);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.sign-out-btn:hover {
  background: color-mix(in oklch, var(--fg-3) 8%, transparent);
  color: var(--fg-2);
}
</style>
