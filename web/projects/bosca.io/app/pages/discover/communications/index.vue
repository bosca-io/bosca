<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Communications — Real-time chat and reliable notifications',
  description: 'Bosca Communications brings together real-time chat, transactional email through SendGrid or Mailgun, and push across mobile, web, and desktop. Every notification respects recipient preferences and records its delivery outcome.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Communications', path: '/discover/communications' }
], '/og-communications.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Converse or notify',
    body: 'Open a real-time chat channel for people to talk in, or send a notification on email and push — both first-class parts of the platform.'
  },
  {
    step: '02',
    title: 'Render per person',
    body: 'A transactional email renders its versioned template at send time with the event\'s data and the recipient\'s locale — complete with email-safe HTML, plain text, and a personalized unsubscribe link.'
  },
  {
    step: '03',
    title: 'Deliver with respect',
    body: 'Every notification clears the recipient\'s preferences and the suppression list first — and afterward you can see whether it reached each person, or why it didn\'t.'
  }
]

const FEATURES = [
  {
    icon: 'message',
    title: 'Real-time channels',
    body: 'Direct, group, and public chat channels, delivered live over subscriptions, with membership and roles.'
  },
  {
    icon: 'heart-pulse',
    title: 'The real-time toolkit',
    body: 'Threaded replies, emoji reactions, typing indicators, and presence — the whole conversation, live.'
  },
  {
    icon: 'send',
    title: 'Email and push',
    body: 'Transactional email through SendGrid or Mailgun, plus push to iOS, Android, web, and desktop — often from a single message.'
  },
  {
    icon: 'file-text',
    title: 'Event templates',
    body: 'An event key resolves to a localized, versioned template at send time, producing email-safe HTML and a plain-text alternative.'
  },
  {
    icon: 'route',
    title: 'Delivery you can see',
    body: 'See whether each message was delivered, deferred, bounced, or dropped — so a silent failure never slips by.'
  },
  {
    icon: 'sliders',
    title: 'Preferences that stick',
    body: 'Per-person, per-channel, per-type opt-outs — while transactional and security messages always get through.'
  },
  {
    icon: 'moon',
    title: 'Quiet hours',
    body: 'Push honors a timezone-aware do-not-disturb window; urgent and security notifications still break through.'
  },
  {
    icon: 'shield-check',
    title: 'Automatic suppression',
    body: 'A hard bounce suppresses the address on its own, and suppressed addresses are skipped before they reach the provider.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="communications"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Communications
        </p>
        <h1 class="load-2">
          Talk in real time.<br>
          <em>Reach them with care.</em>
        </h1>
        <p class="hero-sub load-3">
          Two ways to reach people — real-time chat channels, and transactional
          email and push. Every notification renders from a template, respects
          the recipient's preferences, and reports back whether it actually
          arrived.
        </p>
        <div class="hero-ctas load-4">
          <a
            href="#how"
            class="btn btn-primary"
          >
            How it works
          </a>
        </div>
      </div>

      <div
        class="hero-visual load-4"
        aria-hidden="true"
      >
        <div class="chat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title"># general</span>
          </div>
          <div class="chat-body">
            <div class="chat-msg">
              <span class="chat-av av-m">M</span>
              <div class="chat-bubble">
                <span class="chat-name">Maria</span>
                <span class="chat-text">Ship it 🚀</span>
              </div>
            </div>
            <div class="chat-msg">
              <span class="chat-av av-k">K</span>
              <div class="chat-bubble">
                <span class="chat-name">Kai</span>
                <span class="chat-text">On it — deploying now</span>
                <span class="chat-react">🎉 3</span>
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

    <!-- ── How it works ────────────────────────── -->
    <section
      id="how"
      class="section"
    >
      <div class="section-head reveal">
        <p class="kicker">
          How it works
        </p>
        <h2>Converse, render, <em>deliver.</em></h2>
      </div>
      <div class="how-grid reveal">
        <article
          v-for="item in HOW_IT_WORKS"
          :key="item.step"
          class="how-card"
        >
          <span class="how-step">{{ item.step }}</span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Feature wall ────────────────────────── -->
    <section
      id="features"
      class="features"
    >
      <div class="features-inner">
        <div class="section-head reveal">
          <p class="kicker">
            What makes it powerful
          </p>
          <h2>Live conversation <em>and reliable delivery</em></h2>
          <p class="section-sub">
            The immediacy of real-time chat and the reliability of
            preference-aware email and push that you know got delivered — under
            one roof.
          </p>
        </div>
        <div class="feature-grid">
          <article
            v-for="feature in FEATURES"
            :key="feature.title"
            class="feature-card reveal"
          >
            <span class="feature-icon">
              <Icon
                :name="feature.icon"
                :size="16"
              />
            </span>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ── Every send earns its delivery ───────── -->
    <section class="section gate">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Delivered with care
          </p>
          <h2>Every send <em>earns its delivery</em></h2>
          <p class="section-sub">
            Nothing goes out unchecked. Before a notification reaches the
            provider, it clears the recipient's channel and category
            preferences, a do-not-disturb window for push, and the suppression
            list — and only then does it go out.
          </p>
          <ul class="point-list">
            <li>One gate decides every send: allow, hold for quiet hours, or suppress — with the reason recorded on the delivery record.</li>
            <li>Preferences are per person, per channel, per type — and the ones that matter, transactional and security, can't be turned off.</li>
            <li>A hard bounce adds the address to the suppression list automatically; future sends to it are skipped, not attempted.</li>
            <li>Afterward you can see the outcome for each recipient — delivered, or the reason it wasn't.</li>
          </ul>
        </div>
        <div class="gate-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">message · checkout-receipt</span>
          </div>
          <div class="gate-body">
            <div class="gate-row">
              <span class="gate-check ok"><Icon
                name="check-circle"
                :size="14"
              /></span>
              <span class="gate-label">preference · transactional</span>
              <span class="gate-val ok">allowed</span>
            </div>
            <div class="gate-row">
              <span class="gate-check ok"><Icon
                name="check-circle"
                :size="14"
              /></span>
              <span class="gate-label">suppression · not listed</span>
              <span class="gate-val ok">ok</span>
            </div>
            <div class="gate-row">
              <span class="gate-check ok"><Icon
                name="check-circle"
                :size="14"
              /></span>
              <span class="gate-label">quiet hours · n/a</span>
              <span class="gate-val dim">—</span>
            </div>
            <div class="gate-out">
              <Icon
                name="send"
                :size="13"
              />
              sent · delivered
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverCommunicationsExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#6ea2f7"
        class="closing-mark"
      />
      <h2>Reach people <em>the right way</em></h2>
      <p class="section-sub">
        Real-time channels for the conversation, reliable email and push for the
        moments that matter — each one rendered, gated by preferences, and
        delivered to the person it's for.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.9fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 64px);
  font-weight: 700;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 6px 14px;
  margin-bottom: 26px;
}

.eyebrow-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent);
  animation: discover-pulse 2.4s ease-in-out infinite;
}

.hero-sub {
  font-size: 16.5px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 480px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Chat window ─────────────────────────────── */

.chat-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
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

/* ── How it works ────────────────────────────── */

.how-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.how-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.how-step {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.how-card h3 {
  font-size: 16.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 12px 0 10px;
}

.how-card p {
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}

/* ── Feature wall ────────────────────────────── */

.features {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 45%, transparent);
}

.features-inner {
  max-width: 1140px;
  margin: 0 auto;
  padding: 80px 32px 90px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.feature-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-0) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.feature-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.feature-card h3 {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.feature-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Gate ────────────────────────────────────── */

.gate {
  padding-top: 90px;
}

.gate-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.gate-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.gate-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.gate-check {
  display: flex;
  flex-shrink: 0;
}

.gate-check.ok { color: #34d99a; }

.gate-label {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.gate-val {
  font-family: var(--font-mono);
  font-size: 11px;
}

.gate-val.ok { color: #34d99a; }
.gate-val.dim { color: var(--fg-3); }

.gate-out {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 11.5px;
}

/* ── Closing ─────────────────────────────────── */

.closing {
  max-width: 640px;
  margin: 0 auto;
  padding: 40px 32px 110px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.closing h2 {
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 700;
  margin-top: 18px;
}

.closing .section-sub {
  margin-bottom: 26px;
}

.closing-mark {
  animation: discover-bob 7s ease-in-out infinite;
}

/* ── Motion & responsive ─────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .eyebrow-dot,
  .closing-mark,
  .typing-dots span {
    animation: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: 1fr;
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: 1fr;
  }
}
</style>
