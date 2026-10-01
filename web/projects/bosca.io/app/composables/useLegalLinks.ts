export interface LegalLink {
  label: string
  url: string
}

/**
 * The deployment's legal documents (`NUXT_PUBLIC_PRIVACY_URL` /
 * `NUXT_PUBLIC_TERMS_URL`), limited to the ones that are configured. Prerendered
 * pages capture these at build time, so set them for `nuxt build` as well as
 * at runtime.
 */
export function useLegalLinks(): LegalLink[] {
  const { privacyUrl, termsUrl } = useRuntimeConfig().public
  return [
    { label: 'Privacy Policy', url: privacyUrl },
    { label: 'Terms & Conditions', url: termsUrl }
  ].filter(link => !!link.url)
}
