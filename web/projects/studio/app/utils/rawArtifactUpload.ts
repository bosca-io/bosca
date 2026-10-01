export interface RawArtifactCoordinates {
  namespace: string
  repository: string
  version: string
  filename: string
}

export function rawArtifactUploadUrl(baseUrl: string, coordinates: RawArtifactCoordinates): string {
  const base = baseUrl.replace(/\/+$/, '')
  const encode = encodeURIComponent
  return `${base}/raw/${encode(coordinates.namespace)}/api/${encode(coordinates.repository)}/${encode(coordinates.version)}/${encode(coordinates.filename)}`
}

export async function uploadRawArtifactFile(
  baseUrl: string,
  coordinates: RawArtifactCoordinates,
  file: File,
  authHeaders: Record<string, string>,
  request: typeof fetch = fetch,
): Promise<void> {
  const response = await request(rawArtifactUploadUrl(baseUrl, coordinates), {
    method: 'PUT',
    headers: {
      ...authHeaders,
      'Content-Type': file.type || 'application/octet-stream',
    },
    body: file,
  })

  if (!response.ok) {
    const detail = (await response.text()).trim()
    throw new Error(detail || `Upload failed with status ${response.status}`)
  }
}
