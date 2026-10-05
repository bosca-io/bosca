/**
 * Gates the Recommendations subsystem on administrator access. Mirrors the backend's
 * recommendation admin check (`GroupEvaluator.verifyHasAdminGroup`), where the strategy
 * and placement management surfaces — plus the manual evaluate/test operations — require
 * a global administrator (or SA). Applied per-page via
 * `definePageMeta({ middleware: 'recommendations-admin' })`.
 */
export default defineNuxtRouteMiddleware(async () => {
  const { isAdmin, load } = usePersonas()
  await load()
  if (!isAdmin.value) {
    return navigateTo('/')
  }
})
