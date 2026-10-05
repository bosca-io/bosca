import { describe, it, expect } from 'vitest'
import type { Metadata, Collection } from '~/types/graphql'
import { detectMediaType } from './useMediaType'

function makeMetadata(overrides: Record<string, unknown> = {}) {
  return {
    __typename: 'Metadata' as const,
    id: 'test-id',
    name: 'test',
    attributes: {},
    content: { type: null },
    media: null,
    ...overrides,
  } as unknown as Metadata
}

describe('detectMediaType', () => {
  it('returns empty result for null item and no src', () => {
    const result = detectMediaType(null)
    expect(result.isNativeMedia).toBe(false)
    expect(result.isAudio).toBe(false)
    expect(result.isVideo).toBe(false)
    expect(result.isYouTube).toBe(false)
    expect(result.isHls).toBe(false)
  })

  it('returns empty result for undefined item', () => {
    const result = detectMediaType(undefined)
    expect(result.isNativeMedia).toBe(false)
  })

  describe('audio detection', () => {
    it('detects audio/mpeg', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'audio/mpeg' } }))
      expect(result.isAudio).toBe(true)
      expect(result.isVideo).toBe(false)
      expect(result.isYouTube).toBe(false)
      expect(result.isNativeMedia).toBe(true)
    })

    it('detects audio/wav', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'audio/wav' } }))
      expect(result.isAudio).toBe(true)
      expect(result.isNativeMedia).toBe(true)
    })

    it('provides download URL for audio', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'audio/mpeg' } }))
      expect(result.downloadUrl).toBe('/api/v1/content/metadata/download?id=test-id')
    })
  })

  describe('YouTube detection', () => {
    it('detects YouTube content type', () => {
      const result = detectMediaType(
        makeMetadata({
          content: { type: 'bosca/x-youtube-video' },
          attributes: { 'youtube.id': 'dQw4w9WgXcQ' },
        }),
      )
      expect(result.isYouTube).toBe(true)
      expect(result.isAudio).toBe(false)
      expect(result.isVideo).toBe(false)
      expect(result.isNativeMedia).toBe(true)
      expect(result.youtubeId).toBe('dQw4w9WgXcQ')
    })

    it('returns null youtubeId when attribute is missing', () => {
      const result = detectMediaType(
        makeMetadata({ content: { type: 'bosca/x-youtube-video' } }),
      )
      expect(result.isYouTube).toBe(true)
      expect(result.youtubeId).toBeNull()
    })
  })

  describe('HLS detection', () => {
    it('detects HLS via media.hls.url', () => {
      const result = detectMediaType(
        makeMetadata({
          content: { type: 'video/mp4' },
          media: { hls: { url: 'https://example.com/stream.m3u8' } },
        }),
      )
      expect(result.isHls).toBe(true)
      expect(result.isVideo).toBe(true)
      expect(result.hlsUrl).toBe('https://example.com/stream.m3u8')
    })

    it('detects HLS via attributes["video.hls"]', () => {
      const result = detectMediaType(
        makeMetadata({
          content: { type: 'video/mp4' },
          attributes: { 'video.hls': 'https://example.com/stream.m3u8' },
        }),
      )
      expect(result.isHls).toBe(true)
      expect(result.hlsUrl).toBe('https://example.com/stream.m3u8')
    })

    it('detects HLS via attributes["video"]["hls"]', () => {
      const result = detectMediaType(
        makeMetadata({
          content: { type: 'video/mp4' },
          attributes: { video: { hls: 'https://example.com/stream.m3u8' } },
        }),
      )
      expect(result.isHls).toBe(true)
      expect(result.hlsUrl).toBe('https://example.com/stream.m3u8')
    })

    it('detects HLS via src parameter', () => {
      const result = detectMediaType(null, 'https://example.com/stream.m3u8')
      expect(result.isHls).toBe(true)
      expect(result.isVideo).toBe(true)
      expect(result.hlsUrl).toBe('https://example.com/stream.m3u8')
    })

    it('prioritizes src over item attributes for HLS URL', () => {
      const result = detectMediaType(
        makeMetadata({
          content: { type: 'video/mp4' },
          attributes: { 'video.hls': 'https://example.com/attr.m3u8' },
        }),
        'https://example.com/src.m3u8',
      )
      expect(result.hlsUrl).toBe('https://example.com/src.m3u8')
    })
  })

  describe('direct video detection', () => {
    it('detects video/mp4', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'video/mp4' } }))
      expect(result.isVideo).toBe(true)
      expect(result.isAudio).toBe(false)
      expect(result.isHls).toBe(false)
      expect(result.isNativeMedia).toBe(true)
      expect(result.downloadUrl).toBe('/api/v1/content/metadata/download?id=test-id')
    })

    it('detects video/quicktime', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'video/quicktime' } }))
      expect(result.isVideo).toBe(true)
      expect(result.downloadUrl).toBe('/api/v1/content/metadata/download?id=test-id')
    })

    it('detects generic video/* types as video', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'video/webm' } }))
      expect(result.isVideo).toBe(true)
      expect(result.isNativeMedia).toBe(true)
    })
  })

  describe('non-media types', () => {
    it('returns false for text/plain', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'text/plain' } }))
      expect(result.isNativeMedia).toBe(false)
    })

    it('returns false for application/pdf', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'application/pdf' } }))
      expect(result.isNativeMedia).toBe(false)
    })

    it('returns false for image/jpeg', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'image/jpeg' } }))
      expect(result.isNativeMedia).toBe(false)
    })

    it('returns false for null content type', () => {
      const result = detectMediaType(makeMetadata({ content: { type: null } }))
      expect(result.isNativeMedia).toBe(false)
    })
  })

  describe('collection handling', () => {
    it('does not detect media for Collection type (no content.type)', () => {
      const collection = {
        __typename: 'Collection' as const,
        id: 'coll-id',
        name: 'test collection',
        attributes: {},
      } as unknown as Collection
      const result = detectMediaType(collection)
      expect(result.isNativeMedia).toBe(false)
      expect(result.isAudio).toBe(false)
      expect(result.isVideo).toBe(false)
    })
  })

  describe('download URL', () => {
    it('provides download URL for audio', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'audio/mpeg' } }))
      expect(result.downloadUrl).toBe('/api/v1/content/metadata/download?id=test-id')
    })

    it('provides download URL for direct video', () => {
      const result = detectMediaType(makeMetadata({ content: { type: 'video/mp4' } }))
      expect(result.downloadUrl).toBe('/api/v1/content/metadata/download?id=test-id')
    })

    it('returns null download URL for HLS video', () => {
      const result = detectMediaType(
        makeMetadata({
          content: { type: 'video/webm' },
          media: { hls: { url: 'https://example.com/stream.m3u8' } },
        }),
      )
      expect(result.downloadUrl).toBeNull()
    })

    it('returns null download URL for YouTube', () => {
      const result = detectMediaType(
        makeMetadata({ content: { type: 'bosca/x-youtube-video' } }),
      )
      expect(result.downloadUrl).toBeNull()
    })
  })
})
