<script lang="ts" setup>
 
import 'plyr/dist/plyr.css'
import type PlyrType from 'plyr'
import type HlsType from 'hls.js'
import type { Collection, Metadata } from '~/types/graphql'
import { detectMediaType } from '~/composables/useMediaType'

let PlyrConstructor: typeof PlyrType | null = null

const props = defineProps<{
  src?: string
  poster?: string
  item?: Metadata | Collection | null | undefined
}>()

const emit = defineEmits<{
  (_e: 'timeupdate' | 'seeked' | 'durationchange', _ms: number): void
}>()

const divRef = ref<HTMLDivElement | null>(null)
const audioRef = ref<HTMLAudioElement | null>(null)
const videoRef = ref<HTMLVideoElement | null>(null)
const player = ref<PlyrType | null>(null)
const isReady = ref(false)
let hls: HlsType | null = null

const mediaType = computed(() => detectMediaType(props.item, props.src))

async function setupPlayer() {
  const element = divRef.value || videoRef.value || audioRef.value
  if (!element) return

  player.value?.destroy()
  hls?.destroy()
  player.value = null
  hls = null
  isReady.value = false

  if (!PlyrConstructor) {
    PlyrConstructor = (await import('plyr')).default
  }

  const initPlyr = () => {
    if (player.value || !PlyrConstructor) return

    const options: PlyrType.Options = { autoplay: false }

    if (mediaType.value.isYouTube) {
      options.youtube = {
        noCookie: true,
        rel: 0,
        showinfo: 0,
        iv_load_policy: 3,
        modestbranding: 1,
      }
    }

    if (!mediaType.value.isAudio) {
      options.ratio = '16:9'
    }

    player.value = new PlyrConstructor(element, options)

    player.value.on('timeupdate', () => {
      emit('timeupdate', Math.round((player.value?.currentTime ?? 0) * 1000))
    })
    player.value.on('loadedmetadata', () => {
      emit('durationchange', Math.round((player.value?.duration ?? 0) * 1000))
    })
    player.value.on('seeked', () => {
      emit('seeked', Math.round((player.value?.currentTime ?? 0) * 1000))
    })
    player.value.on('ready', () => {
      setTimeout(() => {
        isReady.value = true
        emit('durationchange', Math.round((player.value?.duration ?? 0) * 1000))
      }, 300)
    })
  }

  const mt = mediaType.value

  if (mt.isYouTube) {
    if (!mt.youtubeId) return
    initPlyr()
  } else if (mt.isAudio && audioRef.value) {
    if (props.src) {
      audioRef.value.src = props.src
    } else if (mt.downloadUrl) {
      audioRef.value.src = mt.downloadUrl
    }
    initPlyr()
  } else if (mt.isHls && videoRef.value) {
    const { default: Hls } = await import('hls.js')
    if (Hls.isSupported()) {
      hls = new Hls()
      hls.loadSource(mt.hlsUrl!)
      hls.attachMedia(videoRef.value)
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        if (!player.value && videoRef.value) {
          initPlyr()
        }
      })
    } else if (
      videoRef.value.canPlayType('application/vnd.apple.mpegurl')
    ) {
      videoRef.value.src = mt.hlsUrl!
      initPlyr()
    }
  } else if (mt.isVideo && mt.downloadUrl && videoRef.value) {
    videoRef.value.src = mt.downloadUrl
    initPlyr()
  }
}

onMounted(() => {
  setupPlayer()
})

watch([() => props.src, () => props.item], () => {
  setupPlayer()
})

defineExpose({
  seek(ms: number) {
    if (player.value) {
      player.value.currentTime = ms / 1000
    }
  },
  get currentTimeMs(): number {
    return Math.round((player.value?.currentTime ?? 0) * 1000)
  },
  get durationMs(): number {
    return Math.round((player.value?.duration ?? 0) * 1000)
  },
  play() {
    player.value?.play()
  },
  pause() {
    player.value?.pause()
  },
  get paused(): boolean {
    return player.value?.paused ?? true
  },
})

onUnmounted(() => {
  player.value?.destroy()
  hls?.destroy()
})
</script>

<template>
  <div class="s-media-player">
    <div v-if="!isReady && item" class="s-media-poster">
      <Icon name="arrowRight" :size="32" color="var(--fg-3)" />
    </div>

    <audio
      v-if="mediaType.isAudio"
      ref="audioRef"
      class="s-media-audio"
    />

    <div
      v-else-if="mediaType.isYouTube && mediaType.youtubeId"
      ref="divRef"
      class="s-media-video"
      data-plyr-provider="youtube"
      :data-plyr-embed-id="mediaType.youtubeId"
    />

    <video
      v-else-if="mediaType.isVideo || src"
      ref="videoRef"
      class="s-media-video"
      playsinline
      controls
      :data-poster="poster"
    />
  </div>
</template>

<style scoped>
.s-media-player {
  position: relative;
  width: 100%;
  border-radius: var(--r-md);
  overflow: hidden;
}

.s-media-poster {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-2);
  aspect-ratio: 16 / 9;
  pointer-events: none;
}

.s-media-audio {
  width: 100%;
}

.s-media-video {
  width: 100%;
  aspect-ratio: 16 / 9;
}
</style>
