<script setup lang="ts">
definePageMeta({ layout: 'home' })

const { hasPersonas, isAdmin, load } = usePersonas()
const { load: loadFeatures } = useServerFeatures()

// Resolve access and feature flags before rendering. Runs on the server too, so
// a user with no access is redirected to /welcome during SSR rather than after
// the client hydrates — no flash of the home shell before the bounce. Feature
// flags are resolved here (not only for gating) so the launcher's category grid,
// which hides feature-disabled subsystems, is correct on first paint.
await Promise.all([load(), loadFeatures()])

const hasAccess = computed(() => hasPersonas.value || isAdmin.value)

// No persona and not an admin: this account can't open any subsystem yet, so an
// empty launcher would be a dead end. Send them to the request-access screen.
if (!hasAccess.value) {
  // Hard navigation on the client so /welcome renders via SSR with its scoped
  // layout CSS; on the server a normal redirect sends the browser straight there.
  await navigateTo('/welcome', { replace: true, external: import.meta.client })
}
</script>

<template>
  <HomeLauncher v-if="hasAccess" />
</template>
