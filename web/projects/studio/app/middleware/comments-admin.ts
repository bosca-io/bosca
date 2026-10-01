/**
 * Gates the CMS Comments pages on the comments module being enabled AND the user
 * being a global admin. The feature check mirrors the deployment flag
 * (`server { features { comments } }` / COMMENTS_ENABLED). The permission check
 * mirrors the backend's moderation bar, where `GroupEvaluator.hasSaGroup` = the
 * `sa` or `administrators` groups. Non-moderators are sent back into CMS.
 *
 * Applied per-page via `definePageMeta({ middleware: 'comments-admin' })`.
 */
export default defineNuxtRouteMiddleware(async () => {
  const { hasGroup, isAdmin, load } = usePersonas()
  const { commentsEnabled, load: loadFeatures } = useServerFeatures()
  await Promise.all([load(), loadFeatures()])
  if (!commentsEnabled.value || (!isAdmin.value && !hasGroup('sa'))) {
    return navigateTo('/cms')
  }
})
