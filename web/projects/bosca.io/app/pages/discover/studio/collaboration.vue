<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Collaboration — Edit together, talk it through',
  description: 'Two people in the same document see each other\'s cursors as they type — across documents, data, collections, guides, and Work Ops specs, requirements, and tasks. A chat dock travels with you, and the content you are discussing goes into the message.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Studio', path: '/discover/studio' },
  { name: 'Collaboration', path: '/discover/studio/collaboration' }
], '/og-studio.png')

const SURFACES = [
  { icon: 'file-text', label: 'Documents' },
  { icon: 'database', label: 'Data' },
  { icon: 'folder', label: 'Collections' },
  { icon: 'book-open', label: 'Guides' },
  { icon: 'list-checks', label: 'Specs' },
  { icon: 'flag', label: 'Requirements' },
  { icon: 'kanban', label: 'Tasks' }
]

const MESSAGES = [
  { who: 'Dana', time: '10:24', body: 'Pushed the new hero copy to the launch doc.', react: '👍 3' },
  { who: 'Miles', time: '10:26', body: 'Looks good. Should we localize before Friday?', react: null },
  { who: 'Dana', time: '10:27', body: 'Yes — starting the pass now.', react: '🎉 2' }
]

const CHANNELS = [
  { name: 'launch', unread: 3 },
  { name: 'content', unread: 0 },
  { name: 'platform', unread: 12 }
]
</script>

<template>
  <DiscoverShell section-id="studio">
    <section class="page-hero">
      <p class="kicker load-1">
        Collaboration
      </p>
      <h1 class="load-2">
        Edit it together, <em>talk it through</em>
      </h1>
      <p class="section-sub load-3">
        Two people in the same document see each other's cursors and each other's
        words as they arrive. And a chat dock travels with you across Studio, so
        the conversation sits beside the work.
      </p>
    </section>

    <!-- ── Live co-editing ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Live editing
          </p>
          <h2>The same page, <em>at the same time</em></h2>
          <p class="section-sub">
            Open a document someone else is already in and you'll see their caret
            move, carrying their name in their own color. Edits merge as they're
            made, so there's no taking turns and no copy that wins. It works the
            same way on data records, collections, and guides — and on Work Ops
            specs, requirements, and tasks.
          </p>
          <ul class="point-list">
            <li>Named, colored cursors show who is where.</li>
            <li>Edits merge as they arrive, from everyone at once.</li>
            <li>The same across content and Work Ops alike.</li>
          </ul>
        </div>
        <div class="doc-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Spring launch checklist</span>
          </div>
          <div class="doc-body">
            <span class="doc-line w80" />
            <span class="doc-line w95" />
            <div class="doc-caret-line">
              <span class="doc-line w40" />
              <span class="caret a"><span class="caret-tag">Dana</span></span>
            </div>
            <span class="doc-line w70" />
            <div class="doc-caret-line">
              <span class="doc-line w55" />
              <span class="caret b"><span class="caret-tag">Miles</span></span>
            </div>
            <span class="doc-line w85" />
          </div>
        </div>
      </div>
    </section>

    <!-- ── Beyond the prose ────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Beyond the prose
          </p>
          <h2>The fields <em>move too</em></h2>
          <p class="section-sub">
            The attributes a template defines, the collections an item belongs to,
            and its relationships all update live for everyone in the record — the
            structure moves along with the prose. And because unsaved work is part of the
            shared state, a draft in progress is visible to whoever opens it next
            — and it's still there after a refresh.
          </p>
          <ul class="point-list">
            <li>Template attributes update live, not just the body.</li>
            <li>Collections and relationships stay in step for everyone.</li>
            <li>Unsaved work is shared, and survives a refresh.</li>
          </ul>
        </div>
        <div class="surf-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">where it works</span>
          </div>
          <div class="surf-body">
            <span
              v-for="s in SURFACES"
              :key="s.label"
              class="surf-chip"
            >
              <Icon
                :name="s.icon"
                :size="12"
              />
              {{ s.label }}
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The dock ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The Collab Dock
          </p>
          <h2>A conversation <em>that follows you</em></h2>
          <p class="section-sub">
            The dock floats above whatever you're working on and stays with you
            from subsystem to subsystem. Drag it where it suits you, size it to
            the room you have, and collapse it to a pill that still shows what's
            waiting. Channels carry their own unread counts, threads keep the
            message they started from in view, and a reaction says the small
            things without another message.
          </p>
          <ul class="point-list">
            <li>Stays with you as you move across Studio.</li>
            <li>Channels with their own unread counts.</li>
            <li>Threads that keep their parent message in view.</li>
            <li>Its place and size are remembered for next time.</li>
          </ul>
        </div>
        <div class="chat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title"># launch</span>
            <span class="chat-badge">3</span>
          </div>
          <div class="chat-body">
            <div
              v-for="m in MESSAGES"
              :key="m.time"
              class="chat-row"
            >
              <span class="chat-avatar">{{ m.who.charAt(0) }}</span>
              <span class="chat-text">
                <span class="chat-meta">
                  <span class="chat-who">{{ m.who }}</span>
                  <span class="chat-time">{{ m.time }}</span>
                </span>
                <span class="chat-body-text">{{ m.body }}</span>
                <span
                  v-if="m.react"
                  class="chat-react"
                >{{ m.react }}</span>
              </span>
            </div>
            <div class="chan-strip">
              <span
                v-for="c in CHANNELS"
                :key="c.name"
                class="chan-pill"
                :class="{ active: c.name === 'launch' }"
              >
                #{{ c.name }}
                <span
                  v-if="c.unread"
                  class="chan-unread"
                >{{ c.unread }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Attach content ──────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Attach the work
        </p>
        <h2>Put the document <em>in the message</em></h2>
        <p class="section-sub">
          Because the chat lives inside the platform that holds your content, you
          can attach a document or a collection to a message and pick it the same
          way you would anywhere else in Studio. The thing you're discussing
          travels with the discussion, instead of being described in it.
        </p>
      </div>
      <div class="compose-window reveal">
        <div class="compose-chip">
          <span class="chip-icon"><Icon
            name="file-text"
            :size="12"
          /></span>
          <span class="chip-name">Spring launch checklist</span>
          <span class="chip-kind">Document</span>
        </div>
        <div class="compose-input">
          <span class="compose-text">Here's the doc we were talking about</span>
          <span class="compose-send"><Icon
            name="send"
            :size="14"
          /></span>
        </div>
      </div>
    </section>

    <DiscoverStudioExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Windows ─────────────────────────────────── */

.doc-window,
.surf-window,
.chat-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Document with carets ────────────────────── */

.doc-body {
  padding: 22px 20px;
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.doc-line {
  display: block;
  height: 9px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 20%, transparent);
}

.w40 { width: 40%; }
.w55 { width: 55%; }
.w70 { width: 70%; }
.w80 { width: 80%; }
.w85 { width: 85%; }
.w95 { width: 95%; }

.doc-caret-line {
  display: flex;
  align-items: center;
  gap: 0;
}

.caret {
  position: relative;
  width: 2px;
  height: 17px;
  margin-left: 3px;
  flex-shrink: 0;
}

.caret.a { background: var(--accent); }
.caret.b { background: #f7828c; }

.caret-tag {
  position: absolute;
  top: -15px;
  left: 0;
  font-size: 9px;
  font-weight: 700;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 3px;
  white-space: nowrap;
  color: #05131c;
}

.caret.a .caret-tag { background: var(--accent); }
.caret.b .caret-tag { background: #f7828c; }

/* ── Surfaces ────────────────────────────────── */

.surf-body {
  padding: 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
}

.surf-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--fg-1);
  border: 1px solid color-mix(in srgb, var(--accent) 28%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  border-radius: var(--r-xs);
  padding: 7px 11px;
}

.surf-chip svg { color: var(--accent); }

/* ── Chat ────────────────────────────────────── */

.chat-badge {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #05131c;
  background: var(--accent);
  border-radius: 999px;
  padding: 1px 7px;
  font-weight: 700;
}

.chat-body {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.chat-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.chat-avatar {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
}

.chat-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.chat-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.chat-who {
  font-size: 12px;
  font-weight: 650;
  color: var(--fg-0);
}

.chat-time {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--fg-4);
}

.chat-body-text {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--fg-2);
}

.chat-react {
  align-self: flex-start;
  margin-top: 3px;
  font-size: 10.5px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 1px 8px;
  color: var(--fg-2);
}

.chan-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding-top: 12px;
  margin-top: 2px;
  border-top: 1px solid var(--line);
}

.chan-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 10px;
}

.chan-pill.active {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.chan-unread {
  font-size: 9px;
  font-weight: 700;
  color: #05131c;
  background: var(--accent);
  border-radius: 999px;
  padding: 0 5px;
}

/* ── Compose ─────────────────────────────────── */

.compose-window {
  max-width: 520px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  padding: 14px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.compose-chip {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 7px 11px;
  margin-bottom: 12px;
  border: 1px solid color-mix(in srgb, var(--accent) 32%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  border-radius: var(--r-xs);
}

.chip-icon { color: var(--accent); display: flex; }

.chip-name {
  font-size: 12px;
  color: var(--fg-1);
}

.chip-kind {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--fg-3);
}

.compose-input {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
}

.compose-text {
  flex: 1;
  font-size: 12.5px;
  color: var(--fg-2);
}

.compose-send { color: var(--accent); display: flex; }
</style>
