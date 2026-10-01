import type { Metadata, Collection } from '~/types/graphql'

export interface MediaTypeResult {
  isAudio: boolean
  isVideo: boolean
  isYouTube: boolean
  isHls: boolean
  isNativeMedia: boolean
  hlsUrl: string | null
  youtubeId: string | null
  downloadUrl: string | null
}

export function detectMediaType(
  item: Metadata | Collection | null | undefined,
  src?: string | null,
): MediaTypeResult {
  const empty: MediaTypeResult = {
    isAudio: false,
    isVideo: false,
    isYouTube: false,
    isHls: false,
    isNativeMedia: false,
    hlsUrl: null,
    youtubeId: null,
    downloadUrl: null,
  }

  if (!item && !src) return empty

  const contentType =
    item?.__typename === 'Metadata' ? (item as Metadata).content?.type ?? null : null
  const attributes = item?.attributes ?? {}
  const media = item?.__typename === 'Metadata' ? (item as Metadata).media : null

  const isAudio = contentType?.startsWith('audio/') === true
  const isYouTube = contentType === 'bosca/x-youtube-video'
  const youtubeId = isYouTube ? (attributes['youtube.id'] as string) ?? null : null

  const hlsUrl =
    src ||
    media?.hls?.url ||
    (attributes['video.hls'] as string) ||
    (attributes['video'] as Record<string, string>)?.['hls'] ||
    null

  const isDirectVideo =
    contentType === 'video/mp4' || contentType === 'video/quicktime'
  const isVideoType = contentType?.startsWith('video/') === true
  const isHls = !isAudio && !isYouTube && !!hlsUrl
  const isVideo = !isAudio && !isYouTube && (isVideoType || isHls)

  const downloadUrl =
    item?.id && (isAudio || isDirectVideo)
      ? `/api/v1/content/metadata/download?id=${item.id}`
      : null

  const isNativeMedia = isAudio || isVideo || isYouTube

  return {
    isAudio,
    isVideo,
    isYouTube,
    isHls,
    isNativeMedia,
    hlsUrl,
    youtubeId,
    downloadUrl,
  }
}

export function useMediaType(
  item:
    | Ref<Metadata | Collection | null | undefined>
    | ComputedRef<Metadata | Collection | null | undefined>,
  src?: Ref<string | undefined> | ComputedRef<string | undefined>,
) {
  return computed(() => detectMediaType(unref(item), unref(src)))
}
