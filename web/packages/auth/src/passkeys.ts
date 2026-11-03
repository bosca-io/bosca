import { BoscaAuthError, NetworkError, UnauthenticatedError } from './errors'

export interface RegisteredPasskey {
  credentialId: string
  name: string
}

interface RegistrationChallenge {
  stateKey: string
  challenge: string
  rp: PublicKeyCredentialRpEntity
  user: { id: string, name: string, displayName: string }
  excludeCredentials: Array<{ id: string, transports: AuthenticatorTransport[] }>
}

function decode(value: string): ArrayBuffer {
  const bytes = Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), character => character.charCodeAt(0))
  return bytes.buffer
}

function encode(value: ArrayBuffer): string {
  return btoa(Array.from(new Uint8Array(value), byte => String.fromCharCode(byte)).join('')).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Runs the browser ceremony against the existing REST endpoints, refreshing auth for each step. */
export async function registerPasskey(
  apiUrl: string, getToken: () => Promise<string | null>, name: string, signal?: AbortSignal,
): Promise<RegisteredPasskey> {
  const trimmedName = name.trim()
  if (!trimmedName || trimmedName.length > 255) throw new BoscaAuthError('Enter a passkey name of 1–255 characters.', 'auth/passkey-name')
  if (typeof navigator === 'undefined' || typeof PublicKeyCredential === 'undefined' || !navigator.credentials?.create) {
    throw new BoscaAuthError('Passkeys are not supported in this browser.', 'auth/passkey-unsupported')
  }
  const post = async <T>(step: string, body: unknown): Promise<T> => {
    const token = await getToken()
    if (!token) throw new UnauthenticatedError()
    let response: Response
    try {
      response = await fetch(`${apiUrl.replace(/\/$/, '')}/api/v1/security/passkeys/register/${step}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        credentials: 'include', body: JSON.stringify(body), signal,
      })
    } catch (error) {
      if (error && (error as { name?: string }).name === 'AbortError') throw error
      throw new NetworkError()
    }
    if (!response.ok) {
      const payload = await response.json().catch(() => null) as { error?: string } | null
      throw new BoscaAuthError('Passkey registration failed. Please try again.', `auth/passkey-${payload?.error ?? 'registration-failed'}`)
    }
    return response.json() as Promise<T>
  }
  const challenge = await post<RegistrationChallenge>('begin', {})
  const credential = await navigator.credentials.create({
    publicKey: {
      challenge: decode(challenge.challenge), rp: challenge.rp,
      user: { ...challenge.user, id: new TextEncoder().encode(challenge.user.id) },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      excludeCredentials: challenge.excludeCredentials.map(item => ({ type: 'public-key', id: decode(item.id), transports: item.transports })),
      authenticatorSelection: { residentKey: 'preferred', userVerification: 'preferred' },
      timeout: 120_000, attestation: 'none',
    }, signal,
  }) as PublicKeyCredential | null
  if (!credential) throw new BoscaAuthError('Passkey registration was cancelled.', 'auth/passkey-cancelled')
  const response = credential.response as AuthenticatorAttestationResponse
  return post<RegisteredPasskey>('complete', {
    stateKey: challenge.stateKey, name: trimmedName, credentialId: encode(credential.rawId),
    attestationObject: encode(response.attestationObject), clientDataJSON: encode(response.clientDataJSON),
    transports: response.getTransports?.() ?? [],
  })
}
