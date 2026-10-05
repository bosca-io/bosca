<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'

definePageMeta({ layout: 'auth' })

const route = useRoute()
const { isAuthenticated } = useAuth()

const accent = '#7c5cff'
const status = ref<'working' | 'error'>('working')
const error = ref('')

onMounted(async () => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string') {
    status.value = 'error'
    error.value = 'This gateway sign-in request is invalid.'
    return
  }

  if (!isAuthenticated.value) {
    await navigateTo(
      `/auth/login?returnTo=${encodeURIComponent(route.fullPath)}`,
      { replace: true, external: true },
    )
    return
  }

  const exchangeUrl = `/api/v1/security/exchange-token?redirect=${encodeURIComponent(redirect)}`
  await navigateTo(exchangeUrl, { replace: true, external: true })
})
</script>

<template>
  <AuthFormCard>
    <AuthHeading
      eyebrow="Gateway sign in"
      title="Connecting your session."
      sub="Studio is securely returning you to the requested service."
      :accent="accent"
    />
    <AuthBanner v-if="status === 'working'" tone="info">Signing you in…</AuthBanner>
    <AuthBanner v-else tone="err">{{ error }}</AuthBanner>
  </AuthFormCard>
</template>
