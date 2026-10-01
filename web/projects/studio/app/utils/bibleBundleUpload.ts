export const BIBLE_CONTENT_TYPE = 'bosca/v-bible'

export const DBL_BUNDLE_ACCEPT = '.zip,application/zip,application/x-zip-compressed'

export interface SignedUploadTarget {
  url: string
  headers: Array<{ name: string; value: string }>
}

export function isBibleContentType(contentType: string | null | undefined): boolean {
  return contentType?.startsWith(BIBLE_CONTENT_TYPE) === true
    || contentType?.startsWith('bosca/x-bible') === true
    || contentType?.startsWith('application/vnd.bosca.v-bible') === true
}

export function isDblBundle(file: File): boolean {
  return file.name.toLowerCase().endsWith('.zip')
}

export async function importDblBundle(
  file: File,
  authHeaders: Record<string, string>,
  request: typeof fetch = fetch,
): Promise<void> {
  if (!isDblBundle(file)) {
    throw new Error('Please select a DBL ZIP bundle.')
  }

  const formData = new FormData()
  formData.append('file-upload', file)
  const response = await request('/api/v1/content/metadata/bibles', {
    method: 'POST',
    body: formData,
    credentials: 'include',
    headers: authHeaders,
  })

  if (!response.ok) {
    const detail = (await response.text()).trim()
    throw new Error(detail || `DBL bundle import failed with status ${response.status}`)
  }
}

export async function uploadMetadataContent(
  target: SignedUploadTarget,
  file: File,
  request: typeof fetch = fetch,
): Promise<void> {
  const headers = new Headers()
  for (const header of target.headers) {
    headers.append(header.name, header.value)
  }

  const formData = new FormData()
  formData.append('file', file)
  const response = await request(target.url, {
    method: 'POST',
    body: formData,
    headers,
  })

  if (!response.ok) {
    const detail = (await response.text()).trim()
    throw new Error(detail || `Content upload failed with status ${response.status}`)
  }
}
