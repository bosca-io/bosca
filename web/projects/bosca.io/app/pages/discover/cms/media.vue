<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Media in the CMS — Adaptive Streaming & Auto-Transcription',
  description: 'Upload video, audio, and images and Bosca handles the rest: adaptive streaming in quality tiers, generated thumbnails and previews, and auto-transcription into searchable subtitle tracks.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'CMS', path: '/discover/cms' },
  { name: 'Media', path: '/discover/cms/media' }
], '/og-cms.png')

const STAGES = [
  { key: 'Upload', note: 'the original lands', done: true },
  { key: 'Preparing', note: 'transcode, thumbnail, transcribe', done: true },
  { key: 'Ready', note: 'streaming, searchable', done: true }
]
</script>

<template>
  <DiscoverShell section-id="cms">
    <section class="page-hero">
      <p class="kicker load-1">
        Media
      </p>
      <h1 class="load-2">
        Upload once. <em>Served everywhere.</em>
      </h1>
      <p class="section-sub load-3">
        Video, audio, and images are content items like any other — but the
        moment one is uploaded, Bosca transcodes it for adaptive streaming,
        generates previews, and transcribes it into searchable text. You
        upload the original; the platform produces everything else.
      </p>
    </section>

    <!-- ── The pipeline ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          The processing pipeline
        </p>
        <h2>From upload to <em>ready</em></h2>
        <p class="section-sub">
          Every media item moves through a lifecycle — Upload, Preparing,
          Ready — and surfaces as Errored if processing fails, so a broken
          asset never silently ships.
        </p>
      </div>
      <div class="pipeline reveal">
        <div
          v-for="(stage, i) in STAGES"
          :key="stage.key"
          class="stage"
        >
          <div class="stage-node">
            <span
              class="stage-dot"
              :class="{ 'stage-done': stage.done }"
            />
            <span class="stage-key">{{ stage.key }}</span>
          </div>
          <p class="stage-note">
            {{ stage.note }}
          </p>
          <Icon
            v-if="i < STAGES.length - 1"
            name="arrow-right"
            :size="16"
            class="stage-arrow"
          />
        </div>
      </div>
    </section>

    <!-- ── Adaptive streaming ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Adaptive streaming
          </p>
          <h2>The right quality, <em>automatically</em></h2>
          <p class="section-sub">
            Video is transcoded for adaptive streaming, so every viewer's
            player picks the quality their connection can carry — no buffering
            on a phone, full fidelity on a desktop. You control how far that
            goes with encoding profiles and a resolution ceiling.
          </p>
          <ul class="point-list">
            <li>Encoding profiles — <code>basic</code>, <code>plus</code>, <code>premium</code> — choose how many quality tiers to produce.</li>
            <li>A maximum resolution tier caps output at 1080p, 1440p, or 2160p, per item or as a global default.</li>
            <li>Media specs are captured on ingest — duration, resolution, aspect ratio, and format.</li>
          </ul>
        </div>
        <div class="player-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">launch-keynote · video</span>
          </div>
          <div class="player">
            <div class="player-stage">
              <Icon
                name="video"
                :size="30"
                class="player-glyph"
              />
            </div>
            <div class="tiers">
              <span class="tier">2160p</span>
              <span class="tier tier-on">1080p</span>
              <span class="tier">720p</span>
              <span class="tier">480p</span>
              <span class="tier-label">auto</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Transcription ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Transcription
          </p>
          <h2>Every word, <em>searchable</em></h2>
          <p class="section-sub">
            Video and audio are transcribed automatically into language-tagged
            tracks — as plain text and as subtitle files. The text is indexed
            for full-text search, so a viewer can find the moment someone said
            a phrase, and the subtitles make the media accessible out of the
            box.
          </p>
          <ul class="point-list">
            <li>Transcripts feed the same search index as the rest of your content.</li>
            <li>Subtitle tracks drop straight into a player as captions.</li>
            <li>Time event types mark named moments on the timeline — chapters, highlights, cues.</li>
          </ul>
        </div>
        <div class="vtt-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">keynote.en.vtt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">WEBVTT</span>

<span class="tok-com">00:00:04.120 --&gt; 00:00:07.480</span>
Welcome — thanks for joining us today.

<span class="tok-com">00:00:07.480 --&gt; 00:00:11.900</span>
We're going to show you the new library.</code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCmsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Processing pipeline ─────────────────────── */

.pipeline {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}

.stage {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-width: 180px;
  padding: 20px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.stage-node {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stage-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  border: 2px solid var(--fg-3);
}

.stage-dot.stage-done {
  border-color: var(--accent);
  background: var(--accent);
}

.stage-key {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--fg-0);
}

.stage-note {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
  margin: 0;
}

.stage-arrow {
  position: absolute;
  right: -13px;
  top: 24px;
  color: var(--accent);
  z-index: 2;
}

/* ── Video player ────────────────────────────── */

.player-window,
.vtt-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.player {
  padding: 18px;
}

.player-stage {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  border-radius: var(--r-sm);
  background:
    radial-gradient(60% 60% at 50% 40%, color-mix(in srgb, var(--accent) 18%, transparent), transparent 70%),
    color-mix(in srgb, var(--bg-1) 70%, #000);
  border: 1px solid var(--line);
}

.player-glyph {
  color: var(--accent);
}

.tiers {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
}

.tier {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px 10px;
}

.tier-on {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}

.tier-label {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-2);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 720px) {
  .stage-arrow {
    display: none;
  }
}
</style>
