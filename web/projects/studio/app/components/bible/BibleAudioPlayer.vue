<script setup lang="ts">
import gql from 'graphql-tag'
import { logInteraction } from '@bosca/analytics-client-browser'
import type { Bible, BibleAudio, BibleAudioChapter } from '~/types/graphql'
import BibleAttribution from './BibleAttribution.vue'

const props = defineProps<{
  bible: Pick<Bible, 'metadataId' | 'version' | 'variant'>
  recordings: Pick<BibleAudio, 'id' | 'name' | 'nameLocal'>[]
  usfm: string
}>()
const { query } = useGraphQL()
const selectedAudio = ref('')
const recording = ref<(Pick<BibleAudioChapter, 'usfm' | 'url' | 'attribution'> & { audio: Pick<BibleAudio, 'id'> }) | null>(null)
const player = ref<HTMLAudioElement | null>(null)
const loading = ref(false)
const error = ref('')
let reported = false

const audioQuery = gql`
  query GetBibleReaderAudio($id: UUID!, $version: Int!, $variant: String!, $audioId: UUID!, $usfm: String!) {
    content {
      metadata(id: $id, version: $version) {
        bible(variant: $variant) {
          audioChapter(audioId: $audioId, usfm: $usfm) {
            audio { id }
            usfm
            url
            attribution { copyright citation informationUrl }
          }
        }
      }
    }
  }
`

watch([selectedAudio, () => props.usfm, () => props.bible], async ([audioId, usfm, bible], _previous, onCleanup) => {
  recording.value = null
  error.value = ''
  reported = false
  loading.value = false
  if (!audioId) return
  let active = true
  onCleanup(() => { active = false; player.value?.pause() })
  loading.value = true
  try {
    const result = await query<{
      content: { metadata: { bible: { audioChapter: NonNullable<typeof recording.value> } } }
    }>(audioQuery, { id: bible.metadataId, version: bible.version, variant: bible.variant, audioId, usfm })
    if (active) recording.value = result.content.metadata.bible.audioChapter
  } catch (e: unknown) {
    if (active) error.value = e instanceof Error ? e.message : 'Failed to load recording'
  } finally {
    if (active) loading.value = false
  }
})

function reportPlayback() {
  const current = recording.value
  if (!current || reported) return
  reported = true
  void logInteraction({
    id: current.usfm,
    type: 'bible-audio',
    extras: {
      bible_id: props.bible.metadataId,
      bible_version: String(props.bible.version),
      bible_variant: props.bible.variant,
      bible_usfm: current.usfm,
      bible_audio_id: current.audio.id,
    },
  }).catch(error => console.error('Failed to report Bible playback', error))
}
</script>

<template>
  <div class="bible-audio">
    <Select
      v-model="selectedAudio"
      label="Recording"
      placeholder="Choose a recording"
      :options="recordings.map(audio => ({ value: audio.id, label: audio.nameLocal || audio.name }))"
      size="sm"
    />
    <p v-if="loading" role="status">Loading recording…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="recording">
      <audio
        :key="recording.url"
        ref="player"
        :src="recording.url"
        controls
        preload="none"
        aria-label="Chapter recording"
        @playing="reportPlayback"
        @error="error = 'Unable to play this recording.'"
      />
      <BibleAttribution :attribution="recording.attribution" />
    </template>
  </div>
</template>

<style scoped>
.bible-audio {
  display: grid;
  gap: 8px;
  margin-bottom: 20px;
}

.bible-audio audio {
  width: 100%;
}

.bible-audio p {
  margin: 0;
  font-size: 13px;
  color: var(--fg-2);
}
</style>
