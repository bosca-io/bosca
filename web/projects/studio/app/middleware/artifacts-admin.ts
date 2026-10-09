export default defineNuxtRouteMiddleware(async () => {
  const { isAdmin, load } = usePersonas()
  await load()
  if (!isAdmin.value) return navigateTo('/artifacts/repositories')
})
