import { describe, expect, it, vi } from 'vitest'
import {
  BIBLE_CONTENT_TYPE,
  importDblBundle,
  isBibleContentType,
  isDblBundle,
  uploadMetadataContent,
} from './bibleBundleUpload'

describe('Bible DBL bundle uploads', () => {
  it('recognizes canonical, extended, and legacy Bible content types', () => {
    expect(isBibleContentType(BIBLE_CONTENT_TYPE)).toBe(true)
    expect(isBibleContentType('bosca/v-bible+json')).toBe(true)
    expect(isBibleContentType('bosca/x-bible')).toBe(true)
    expect(isBibleContentType('application/vnd.bosca.v-bible')).toBe(true)
    expect(isBibleContentType('application/zip')).toBe(false)
    expect(isBibleContentType(null)).toBe(false)
  })

  it('accepts ZIP filenames case-insensitively', () => {
    expect(isDblBundle(new File(['bundle'], 'web.ZIP'))).toBe(true)
    expect(isDblBundle(new File(['bundle'], 'web.usx'))).toBe(false)
  })

  it('posts the DBL ZIP to the Bible importer with refreshed auth headers', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 201, text: async () => '' })
    const file = new File(['bundle'], 'web.zip', { type: 'application/zip' })

    await importDblBundle(
      file,
      { Authorization: 'Bearer refreshed-token' },
      request as unknown as typeof fetch,
    )

    expect(request).toHaveBeenCalledOnce()
    const [url, options] = request.mock.calls[0]!
    expect(url).toBe('/api/v1/content/metadata/bibles')
    expect(options).toMatchObject({
      method: 'POST',
      credentials: 'include',
      headers: { Authorization: 'Bearer refreshed-token' },
    })
    expect((options.body as FormData).get('file-upload')).toBe(file)
  })

  it('rejects non-ZIP files before making a request', async () => {
    const request = vi.fn()

    await expect(importDblBundle(
      new File(['bundle'], 'web.usx'),
      {},
      request as unknown as typeof fetch,
    )).rejects.toThrow('Please select a DBL ZIP bundle.')
    expect(request).not.toHaveBeenCalled()
  })

  it('surfaces importer failures', async () => {
    const request = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => 'missing metadata',
    })

    await expect(importDblBundle(
      new File(['bundle'], 'bad.zip'),
      {},
      request as unknown as typeof fetch,
    )).rejects.toThrow('missing metadata')
  })

  it('uploads replacement content to a signed metadata URL', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 201, text: async () => '' })
    const file = new File(['bundle'], 'web.zip', { type: 'application/zip' })

    await uploadMetadataContent({
      url: 'https://studio.example.test/api/v1/content/metadata/upload?signed=true',
      headers: [{ name: 'Authorization', value: 'Bearer upload-token' }],
    }, file, request as unknown as typeof fetch)

    const [url, options] = request.mock.calls[0]!
    expect(url).toBe('https://studio.example.test/api/v1/content/metadata/upload?signed=true')
    expect(options.method).toBe('POST')
    expect((options.headers as Headers).get('Authorization')).toBe('Bearer upload-token')
    expect((options.body as FormData).get('file')).toBe(file)
  })

  it('surfaces signed metadata upload failures', async () => {
    const request = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      text: async () => '',
    })

    await expect(uploadMetadataContent(
      { url: '/upload', headers: [] },
      new File(['bundle'], 'web.zip'),
      request as unknown as typeof fetch,
    )).rejects.toThrow('Content upload failed with status 403')
  })
})
