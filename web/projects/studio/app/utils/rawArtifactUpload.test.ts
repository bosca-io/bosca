import { describe, expect, it, vi } from 'vitest'
import { rawArtifactUploadUrl, uploadRawArtifactFile } from './rawArtifactUpload'

describe('raw artifact uploads', () => {
  it('builds an encoded raw API push URL', () => {
    expect(rawArtifactUploadUrl('https://artifacts.example.com/', {
      namespace: '@team',
      repository: 'release files',
      version: '1.0.0+build 4',
      filename: 'app macOS.zip',
    })).toBe('https://artifacts.example.com/raw/%40team/api/release%20files/1.0.0%2Bbuild%204/app%20macOS.zip')
  })

  it('uploads the file with auth and its media type', async () => {
    const request = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      text: async () => '',
    })
    const file = new File(['artifact'], 'artifact.tar.gz', { type: 'application/gzip' })

    await uploadRawArtifactFile(
      'http://localhost:8080',
      { namespace: 'bosca', repository: 'cli', version: '1.2.3', filename: file.name },
      file,
      { Authorization: 'Bearer studio-token' },
      request as unknown as typeof fetch,
    )

    expect(request).toHaveBeenCalledWith(
      'http://localhost:8080/raw/bosca/api/cli/1.2.3/artifact.tar.gz',
      {
        method: 'PUT',
        headers: {
          Authorization: 'Bearer studio-token',
          'Content-Type': 'application/gzip',
        },
        body: file,
      },
    )
  })

  it('surfaces the raw endpoint response on failure', async () => {
    const request = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      text: async () => 'Insufficient namespace permission',
    })

    await expect(uploadRawArtifactFile(
      '',
      { namespace: 'bosca', repository: 'cli', version: '1', filename: 'cli' },
      new File(['artifact'], 'cli'),
      {},
      request as unknown as typeof fetch,
    )).rejects.toThrow('Insufficient namespace permission')
  })
})
