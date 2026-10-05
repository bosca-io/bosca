/**
 * Maps a content type to the appropriate editor route for that item.
 * Centralizes routing logic so every list/detail page navigates consistently.
 */
export function getEditorRoute(id: string, contentType: string | null | undefined): string {
  const ct = contentType ?? ''

  if (ct.includes('template')) return `/cms/settings/templates/${id}`
  if (ct.startsWith('bosca/v-guide') || ct.startsWith('application/vnd.bosca.v-guide')) return `/cms/guides/${id}`
  if (ct.startsWith('bosca/v-bible') || ct.startsWith('application/vnd.bosca.v-bible')) return `/cms/bibles/${id}/reader`
  if (ct.startsWith('bosca/v-data') || ct === 'application/json') return `/cms/data/${id}`
  if (ct.startsWith('bosca/v-document') || ct.startsWith('application/vnd.bosca.v-document')) return `/cms/editor/${id}`

  if (ct.startsWith('video/') || ct.startsWith('image/') || ct.startsWith('audio/')) return `/cms/metadata/${id}`

  return `/cms/editor/${id}`
}
