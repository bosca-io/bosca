import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BoscaAuth } from './client'
import { BoscaAuthError, NetworkError, UnauthenticatedError } from './errors'
import { registerPasskey } from './passkeys'

const challenge = {
  stateKey: 'one-time-state', challenge: '-_8', rp: { id: 'example.test', name: 'Bosca' },
  user: { id: 'principal-id', name: 'Account', displayName: 'Account' },
  excludeCredentials: [{ id: '-_8', transports: ['usb'] }],
}
const bytes = (value: number[]) => Uint8Array.from(value).buffer
const credential = () => ({ type: 'public-key', rawId: bytes([251, 255]), response: {
  attestationObject: bytes([1, 2]), clientDataJSON: bytes([3, 4]), getTransports: () => ['internal'],
} })
let create = vi.fn()
let fetchMock = vi.fn()
let auth: BoscaAuth | undefined
const run = (name = 'Key', signal?: AbortSignal) => registerPasskey('', async () => 'account-token', name, signal)

beforeEach(() => {
  create = vi.fn().mockResolvedValue(credential())
  fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(challenge)))
    .mockResolvedValue(new Response('{"credentialId":"-_8","name":"Key"}'))
  vi.stubGlobal('navigator', { credentials: { create } })
  vi.stubGlobal('PublicKeyCredential', class {})
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => { auth?.destroy(); auth = undefined; vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('passkey registration', () => {
  it('uses fresh SDK auth for both steps and converts WebAuthn binary data', async () => {
    auth = new BoscaAuth({ apiUrl: 'https://api.example.test/', storage: 'memory', autoRefresh: false })
    const token = vi.spyOn(auth, 'getToken').mockResolvedValueOnce('first-token').mockResolvedValueOnce('renewed-token')
    const signal = new AbortController().signal
    await expect(auth.registerPasskey(' Key ', signal)).resolves.toEqual({ credentialId: '-_8', name: 'Key' })
    expect(token).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      'https://api.example.test/api/v1/security/passkeys/register/begin',
      'https://api.example.test/api/v1/security/passkeys/register/complete',
    ])
    const options = create.mock.calls[0][0] as CredentialCreationOptions
    expect(Array.from(new Uint8Array(options.publicKey!.challenge as ArrayBuffer))).toEqual([251, 255])
    expect(new TextDecoder().decode(options.publicKey!.user.id)).toBe('principal-id')
    expect(Array.from(new Uint8Array(options.publicKey!.excludeCredentials![0].id as ArrayBuffer))).toEqual([251, 255])
    expect(options.publicKey!.excludeCredentials![0].transports).toEqual(['usb'])
    expect(options.publicKey!.pubKeyCredParams.map(value => value.alg)).toEqual([-7, -257])
    expect(options.publicKey!.attestation).toBe('none')
    expect(options.signal).toBe(signal)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer first-token')
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer renewed-token')
    expect(fetchMock.mock.calls[1][1].credentials).toBe('include')
    expect(fetchMock.mock.calls[1][1].signal).toBe(signal)
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      stateKey: 'one-time-state', name: 'Key', credentialId: '-_8', attestationObject: 'AQI', clientDataJSON: 'AwQ', transports: ['internal'],
    })
  })
  it('supports authenticators without a transport reporting method', async () => {
    create.mockResolvedValue({ ...credential(), response: { attestationObject: bytes([1]), clientDataJSON: bytes([2]) } })
    await run()
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).transports).toEqual([])
  })
  it.each(['', ' ', 'x'.repeat(256)])('rejects invalid names before a ceremony', async name => {
    await expect(run(name)).rejects.toMatchObject({ code: 'auth/passkey-name' })
    expect(fetchMock).not.toHaveBeenCalled()
  })
  it.each([
    () => vi.stubGlobal('navigator', undefined),
    () => vi.stubGlobal('PublicKeyCredential', undefined),
    () => vi.stubGlobal('navigator', {}),
    () => vi.stubGlobal('navigator', { credentials: {} }),
  ])('reports unsupported environments without requesting a challenge', async unsupported => {
    unsupported()
    await expect(run()).rejects.toMatchObject({ code: 'auth/passkey-unsupported' })
    expect(fetchMock).not.toHaveBeenCalled()
  })
  it('requires an authenticated account before posting', async () => {
    await expect(registerPasskey('', async () => null, 'Key')).rejects.toBeInstanceOf(UnauthenticatedError)
    expect(fetchMock).not.toHaveBeenCalled()
  })
  it('retains native authenticator cancellation without completing', async () => {
    const cancelled = new DOMException('Cancelled', 'NotAllowedError')
    create.mockRejectedValue(cancelled)
    await expect(run()).rejects.toBe(cancelled)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('reports a dismissed empty credential', async () => {
    create.mockResolvedValue(null)
    await expect(run()).rejects.toMatchObject({ code: 'auth/passkey-cancelled' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it.each([new TypeError('offline'), null])('reports network failure', async failure => {
    fetchMock.mockReset().mockRejectedValue(failure)
    await expect(run()).rejects.toBeInstanceOf(NetworkError)
  })
  it('preserves abort errors from another realm', async () => {
    const aborted = { name: 'AbortError', message: 'Cancelled' }
    fetchMock.mockReset().mockRejectedValue(aborted)
    await expect(run()).rejects.toBe(aborted)
  })
  it.each([
    ['{"error":"challenge_expired"}', 'auth/passkey-challenge_expired'],
    ['{}', 'auth/passkey-registration-failed'], ['null', 'auth/passkey-registration-failed'],
    ['unavailable', 'auth/passkey-registration-failed'],
  ])('keeps API failures visible: %s', async (body, code) => {
    fetchMock.mockReset().mockResolvedValue(new Response(body, { status: 400 }))
    await expect(run()).rejects.toMatchObject({ code })
    expect(create).not.toHaveBeenCalled()
  })
  it('reports rejected attestation without retrying the one-time challenge', async () => {
    fetchMock.mockReset().mockResolvedValueOnce(new Response(JSON.stringify(challenge)))
      .mockResolvedValueOnce(new Response('{"error":"verification_failed"}', { status: 400 }))
    await expect(run()).rejects.toBeInstanceOf(BoscaAuthError)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
