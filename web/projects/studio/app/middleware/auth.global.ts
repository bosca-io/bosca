export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path.startsWith('/auth/') || to.path === '/welcome' || to.path === '/unsubscribe') return

  // Unauthenticated users are redirected to login. On the server this is
  // already enforced upstream by the Nitro auth middleware
  // (server/middleware/auth.ts) before this route middleware runs, so the
  // reactive check is only needed on the client.
  if (import.meta.client) {
    const { $auth } = useNuxtApp()
    if (!$auth) return
    if (!$auth.isAuthenticated) {
      return navigateTo('/auth/login')
    }
  }

  // The index page owns subsystem routing for "/".
  if (to.path === '/') return

  // Resolve persona/admin access and the deployment's feature flags. Both are
  // awaited on the server so the SSR payload is authoritative: a user with no
  // persona is redirected to /welcome during SSR (no flash of the app shell),
  // and the nav renders with feature-disabled subsystems (e.g. commerce when
  // ecommerce is off) already removed rather than flashing in then out.
  const { hasPersonas, isAdmin, load } = usePersonas()
  const { load: loadFeatures } = useServerFeatures()
  await Promise.all([load(), loadFeatures()])

  if (!hasPersonas.value && !isAdmin.value) {
    // Client: external navigation forces a full document load so /welcome
    // renders via SSR with its scoped layout CSS intact (a client-side layout
    // switch drops the welcome layout's styles until a refresh). Server: we are
    // already mid-SSR, so a normal redirect sends the browser to a fresh
    // /welcome.
    return navigateTo('/welcome', { replace: true, external: import.meta.client })
  }
})
