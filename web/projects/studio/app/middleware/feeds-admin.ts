/**
 * Gates the Feeds subsystem on administrator access. Mirrors the backend's feeds admin check
 * (`GroupEvaluator.verifyHasAdminGroup` / the `feeds.administrator` group), where the managed
 * feed-source read/write surface and the cross-source item previews require a global administrator
 * (or SA). Applied per-page via `definePageMeta({ middleware: 'feeds-admin' })`.
 */
export default defineNuxtRouteMiddleware(async () => {
  const { isAdmin, load } = usePersonas()
  await load()
  if (!isAdmin.value) {
    return navigateTo('/')
  }
})
