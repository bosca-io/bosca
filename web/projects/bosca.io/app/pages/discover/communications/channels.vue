<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Channels — Real-time chat on Bosca',
  description: 'Direct, group, and public chat channels delivered live over GraphQL subscriptions — with threaded replies, emoji reactions, typing indicators, presence, and channels that can attach to any object on the platform.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Communications', path: '/discover/communications' },
  { name: 'Channels', path: '/discover/communications/channels' }
], '/og-communications.png')

const TYPES = [
  { icon: 'user', name: 'Direct', body: 'A private one-on-one conversation between two people.' },
  { icon: 'users', name: 'Group', body: 'A conversation visible only to the people who belong to it.' },
  { icon: 'globe', name: 'Public', body: 'An open channel any eligible member can find and join.' }
]

const SCOPES = ['a document', 'a collection', 'a localization key', 'a calendar event', 'a feature flag', 'an experiment']
</script>

<template>
  <DiscoverShell section-id="communications">
    <section class="page-hero">
      <p class="kicker load-1">
        Channels
      </p>
      <h1 class="load-2">
        Conversations, <em>in real time</em>
      </h1>
      <p class="section-sub load-3">
        Channels are where people talk on Bosca. Messages arrive the instant
        they're sent — over live subscriptions, not polling — with the whole
        toolkit you expect: threads, reactions, typing, and presence.
      </p>
    </section>

    <!-- ── Channel types ───────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Channel types
        </p>
        <h2>Three shapes <em>of conversation</em></h2>
      </div>
      <div class="type-grid reveal">
        <article
          v-for="t in TYPES"
          :key="t.name"
          class="type-card"
        >
          <span class="type-icon">
            <Icon
              :name="t.icon"
              :size="16"
            />
          </span>
          <h3>{{ t.name }}</h3>
          <p>{{ t.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Real-time toolkit ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The real-time toolkit
          </p>
          <h2>Everything a <em>live chat needs</em></h2>
          <p class="section-sub">
            Each channel carries an ordered stream of messages with sender,
            time, and rich content. Replies nest into threads, reactions attach
            per person, and typing and presence keep the room feeling alive.
          </p>
          <ul class="point-list">
            <li>Threaded replies nest under the message they answer.</li>
            <li>Emoji reactions, typing indicators, and presence, all delivered live.</li>
            <li>Read state per member drives unread badges without re-reading history.</li>
            <li>Messages carry text, images, files, and links to platform objects.</li>
          </ul>
        </div>
        <div class="chat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title"># launch</span>
          </div>
          <div class="chat-body">
            <div class="chat-msg">
              <span class="chat-av av-m">M</span>
              <div class="chat-bubble">
                <span class="chat-name">Maria</span>
                <span class="chat-text">Docs are ready for review</span>
                <span class="chat-react">👍 2</span>
              </div>
            </div>
            <div class="chat-msg thread">
              <span class="chat-av av-k">K</span>
              <div class="chat-bubble">
                <span class="chat-name">Kai <span class="chat-reply">↳ reply</span></span>
                <span class="chat-text">On it — merging now</span>
              </div>
            </div>
            <div class="chat-typing">
              <span class="typing-dots"><span /><span /><span /></span>
              Ada is typing…
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Object-scoped ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Scoped to your work
          </p>
          <h2>A channel for <em>anything on the platform</em></h2>
          <p class="section-sub">
            A channel doesn't have to float free. Attach one to an object on the
            platform and the discussion lives right next to the thing it's
            about — so the conversation and the work never drift apart.
          </p>
          <ul class="point-list">
            <li>Scope a channel to an object and it travels with it.</li>
            <li>The people who can see the object are the people in the room.</li>
            <li>Free-form attributes let a channel carry its own context.</li>
          </ul>
        </div>
        <div class="scope-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">attach a channel to…</span>
          </div>
          <div class="scope-body">
            <div
              v-for="s in SCOPES"
              :key="s"
              class="scope-row"
            >
              <Icon
                name="link"
                :size="13"
              />
              {{ s }}
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCommunicationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Type cards ──────────────────────────────── */

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.type-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.type-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.type-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.type-card h3 {
  font-size: 15.5px;
  font-weight: 650;
  margin: 0 0 8px;
}

.type-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Chat window ─────────────────────────────── */

.chat-window,
.scope-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.chat-body {
  padding: 18px 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.chat-msg {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.chat-msg.thread {
  padding-left: 26px;
}

.chat-av {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: #05131c;
}

.chat-av.av-m { background: var(--accent); }
.chat-av.av-k { background: #34d99a; }

.chat-bubble {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.chat-name {
  font-size: 12px;
  font-weight: 650;
  color: var(--fg-1);
}

.chat-reply {
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 400;
  color: var(--fg-3);
}

.chat-text {
  font-size: 14px;
  color: var(--fg-0);
}

.chat-react {
  align-self: flex-start;
  margin-top: 5px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 9px;
}

.chat-typing {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-left: 42px;
  font-size: 12.5px;
  color: var(--fg-3);
}

.typing-dots {
  display: inline-flex;
  gap: 3px;
}

.typing-dots span {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: var(--accent);
  animation: discover-pulse 1.4s ease-in-out infinite;
}

.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }

/* ── Scope window ────────────────────────────── */

.scope-body {
  padding: 12px 10px;
}

.scope-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 13px 14px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.scope-row + .scope-row {
  border-top: 1px solid var(--line);
}

.scope-row svg {
  color: var(--accent);
  flex-shrink: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .typing-dots span {
    animation: none;
  }
}

@media (max-width: 760px) {
  .type-grid {
    grid-template-columns: 1fr;
  }
}
</style>
