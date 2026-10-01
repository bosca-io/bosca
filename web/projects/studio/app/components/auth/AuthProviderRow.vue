<script setup lang="ts">
const props = defineProps<{
  returnTo?: string
}>()

const providers = [
  { id: 'google', label: 'Continue with Google' },
  // { id: 'microsoft', label: 'Microsoft' },
  // { id: 'github', label: 'GitHub' },
] as const

// Return to /auth/login — it's allow-listed in the SSR auth guard (so the
// cookie-less OAuth return isn't bounced) and does no server-side redirect, so
// the ?exchangeToken=... survives to the client where 01.auth.ts redeems it.
// Returning to "/" instead loses the token to the guard / index.vue redirect.
const requestUrl = useRequestURL()
requestUrl.pathname = '/auth/login'
requestUrl.search = ''
requestUrl.hash = ''
if (props.returnTo?.startsWith('/auth/gateway?')) {
  requestUrl.searchParams.set('returnTo', props.returnTo)
}

function oauthUrl(provider: string) {
  // `originator=studio` identifies the originating client; it is stamped on a newly-created credential
  // and echoed back on the login response.
  return `/oauth2/${provider}/login?redirect=${encodeURIComponent(requestUrl.toString())}&originator=studio`
}
</script>

<template>
  <div class="provider-grid">
    <a
      v-for="p in providers"
      :key="p.id"
      :href="oauthUrl(p.id)"
      class="provider-btn">
      <AuthIcon :name="p.id" :size="16" /> {{ p.label }}
    </a>
  </div>
</template>

<style scoped>
.provider-grid {
  display: grid;
  grid-template-columns: repeat(1, 1fr);
  gap: 8px;
}
.provider-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 40px;
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  color: var(--fg-1);
}
</style>
