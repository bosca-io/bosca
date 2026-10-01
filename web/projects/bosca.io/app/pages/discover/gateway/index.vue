<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Gateway — Your Services, Behind One Front Door',
  description: 'The Bosca Gateway is a managed reverse proxy: put any HTTP service you run behind a Bosca-authenticated path. Your sign-in, your groups, and your rules decide who gets through.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Gateway', path: '/discover/gateway' }
], '/og-gateway.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Register the upstream',
    body: 'Point a gateway at the service\'s URL, give it a health probe, and tune its timeouts and connection pool.'
  },
  {
    step: '02',
    title: 'Bind a route',
    body: 'Match a path pattern — and optionally a host — to that upstream. Pick the sign-in method and the groups allowed to read and to write.'
  },
  {
    step: '03',
    title: 'Traffic flows',
    body: 'The proxy authenticates each request, injects the caller\'s identity as headers if you want it to, and forwards — reporting the upstream\'s health back to Studio.'
  }
]

const FEATURES = [
  {
    icon: 'globe',
    title: 'Any HTTP service',
    body: 'Dashboards, query engines, internal tools, that one legacy box — if it speaks HTTP, it can sit behind the gateway.'
  },
  {
    icon: 'lock',
    title: 'Bosca sign-in in front',
    body: 'Per route: tokens for automation, the Studio session for people, username-and-password for older tools, or public — the same accounts as the rest of the platform.'
  },
  {
    icon: 'users',
    title: 'Groups decide access',
    body: 'Reading and writing are granted separately, each to the groups you choose. API tokens also need the matching gateway scope.'
  },
  {
    icon: 'heart-pulse',
    title: 'Health, watched',
    body: 'The proxy probes each upstream on your interval and reports up, down, or unknown — with the failure reason — right in Studio.'
  },
  {
    icon: 'route',
    title: 'Precise matching',
    body: 'Prefix, single-segment, and exact path patterns; literal and wildcard hosts with clear precedence; a sort order for ties.'
  },
  {
    icon: 'send',
    title: 'Identity, forwarded',
    body: 'Inject headers on the way through — including the caller\'s email, subject, and groups — so the upstream knows who\'s asking.'
  },
  {
    icon: 'sliders',
    title: 'Tuned per upstream',
    body: 'Connect and request timeouts, pool size, and idle limits are set on each gateway, not globally.'
  },
  {
    icon: 'zap',
    title: 'Presets for the fiddly ones',
    body: 'A preset fills in the fields a service is picky about — the data-warehouse preset sets the path and prefix handling, and flags what the warehouse itself needs configured.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="gateway"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Gateway
        </p>
        <h1 class="load-2">
          Your services,<br>
          <em>behind one front door.</em>
        </h1>
        <p class="hero-sub load-3">
          The Gateway is a managed reverse proxy: put any HTTP service you run
          behind a Bosca-authenticated path. Your sign-in, your groups, and
          your rules decide who gets through.
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
        <div class="route-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">route</span>
          </div>
          <div class="route-body">
            <div class="rt-line">
              <span class="rt-path">/warehouse/**</span>
              <Icon
                name="arrow-right"
                :size="13"
                class="rt-arrow"
              />
              <span class="rt-target">warehouse</span>
            </div>
            <div class="rt-meta">
              <div class="rt-cell">
                <span class="rc-key">Auth</span>
                <span class="rc-val">OAuth2 session</span>
              </div>
              <div class="rt-cell">
                <span class="rc-key">Read</span>
                <span class="rc-val">analysts</span>
              </div>
              <div class="rt-cell">
                <span class="rc-key">Write</span>
                <span class="rc-val">data-eng</span>
              </div>
              <div class="rt-cell">
                <span class="rc-key">Upstream</span>
                <span class="rc-val ok">UP</span>
              </div>
            </div>
            <div class="rt-forward">
              <Icon
                name="send"
                :size="12"
              />
              forwards with <code>X-User: jane@example.com</code>
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
        <h2>Register, bind, <em>route.</em></h2>
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
          <h2>A real proxy. <em>And part of the platform.</em></h2>
          <p class="section-sub">
            The routing, health checks, and tuning you expect from a reverse
            proxy — with the platform's own accounts, groups, and tokens
            deciding who gets through.
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

    <!-- ── Integration ─────────────────────────── -->
    <section class="section integ">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deep platform integration
          </p>
          <h2>Not a silo — <em>part of the whole</em></h2>
          <p class="section-sub">
            The gateway doesn't bring its own user database or its own admin
            tool. It borrows the platform's — and that's the point.
          </p>
          <ul class="point-list">
            <li>Sign-in is Bosca's sign-in — the same accounts, browser sessions, and API tokens as everything else. No separate credentials to manage.</li>
            <li>Access rules are Bosca groups — the group that runs your analytics is the group that reaches the warehouse behind the gateway.</li>
            <li>Everything is managed in Studio — upstreams, routes, and live health on one screen. The proxy picks up changes on its own; there's no restart and no config file to ship.</li>
            <li>Upstreams learn who's calling — injected headers carry the signed-in user's identity into services that have no idea what Bosca is.</li>
          </ul>
        </div>
        <div class="integ-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">connected</span>
          </div>
          <div class="integ-body">
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="users"
                :size="15"
              /></span>
              <span class="integ-text">Access via the <code>analysts</code> group</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="key"
                :size="15"
              /></span>
              <span class="integ-text">API token · scope <code>gateway:read</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="send"
                :size="15"
              /></span>
              <span class="integ-text">Identity headers → upstream</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="heart-pulse"
                :size="15"
              /></span>
              <span class="integ-text">Upstream health, live in Studio</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverGatewayExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#c3cf3a"
        class="closing-mark"
      />
      <h2>Gateway ships <em>with Bosca</em></h2>
      <p class="section-sub">
        Every platform accumulates internal services that deserve better than
        a bare port. Put them behind the front door you already have.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
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
  max-width: 470px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Route window ────────────────────────────── */

.route-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.route-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.rt-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rt-path {
  font-family: var(--font-mono);
  font-size: 14px;
  color: var(--fg-0);
}

.rt-arrow {
  color: var(--fg-3);
}

.rt-target {
  font-size: 13px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.rt-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.rt-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 9px 11px;
}

.rc-key {
  font-size: 10.5px;
  color: var(--fg-3);
}

.rc-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.rc-val.ok {
  color: #34d99a;
}

.rt-forward {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
}

.rt-forward code {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

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
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
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

/* ── Integration ─────────────────────────────── */

.integ {
  padding-top: 90px;
}

.integ-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.integ-body {
  padding: 10px 8px;
}

.integ-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.integ-row + .integ-row {
  border-top: 1px solid var(--line);
}

.integ-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
}

.integ-text {
  font-size: 13px;
  color: var(--fg-1);
}

.integ-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
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
  .closing-mark {
    animation: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
