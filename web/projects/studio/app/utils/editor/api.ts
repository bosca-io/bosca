export async function executeYDocRequest(url: string, method: string, token: string, body: Uint8Array | null = null) {
  const headers = new Headers()
  headers.set('Content-Type', 'application/octet-stream')
  headers.set('Accept', 'application/octet-stream')
  headers.set('Authorization', 'Bearer ' + token)
  const request: RequestInit = {
    headers,
    method,
    body: body as BodyInit | null
  }
  const response = await fetch(url, request)
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    console.error(`Collaboration request failed: ${response.status} ${response.statusText}`)
    return null
  }
  return await response.bytes()
}
