/**
 * Gates the Commerce subsystem on the ecommerce module being enabled AND the
 * `ecom.administrator` group. The feature check mirrors the deployment flag
 * (`server { features { ecommerce } }` / ECOMMERCE_ENABLED): when the module is
 * off its GraphQL surface and the ecom.administrator group don't exist, so the
 * route is blocked even for global admins. The permission check mirrors the
 * backend's ecom-admin check (GroupEvaluator), where global `administrators` are
 * superusers and otherwise membership in `ecom.administrator` is required.
 * Applied per-page via `definePageMeta({ middleware: 'commerce-admin' })`.
 */
export default defineNuxtRouteMiddleware(async () => {
  const { hasGroup, isAdmin, load } = usePersonas()
  const { ecommerceEnabled, load: loadFeatures } = useServerFeatures()
  await Promise.all([load(), loadFeatures()])
  if (!ecommerceEnabled.value) {
    return navigateTo('/')
  }
  if (!isAdmin.value && !hasGroup('ecom.administrator')) {
    return navigateTo('/')
  }
})
