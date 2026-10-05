<script setup lang="ts">
import { SECTIONS, groupSections } from '~/composables/useSections'

definePageMeta({ layout: false })

// A subsystem chip renders as a NuxtLink when it has a marketing page, else a
// plain div. Resolve the component here — `<component :is="'NuxtLink'">` with a
// string name does not resolve the auto-imported component and leaks a literal
// <NuxtLink> tag into SSR output.
const NuxtLink = resolveComponent('NuxtLink')

// Capability chips for sections that have no marketing page of their own still
// invite a conversation: they open the "learn more" form instead of navigating.
// The chip's accent tints the modal so it reads as coming from that section.
const LEARN_MORE_SECTIONS = new Set(['helm'])
const opensLearnMore = (id: string) => LEARN_MORE_SECTIONS.has(id)
const learnMore = ref<string | null>(null)

const siteUrl = useRuntimeConfig().public.siteUrl
const legalLinks = useLegalLinks()

useHead({
  // The landing page's title is already fully branded — no docs suffix.
  titleTemplate: null,
  script: [
    {
      type: 'application/ld+json',
      innerHTML: JSON.stringify({
        '@context': 'https://schema.org',
        '@graph': [
          {
            '@type': 'Organization',
            'name': 'Bosca',
            'url': siteUrl,
            'logo': `${siteUrl}/logo.svg`
          },
          {
            '@type': 'WebSite',
            'name': 'Bosca',
            'url': siteUrl
          }
        ]
      })
    }
  ]
})

useSeoMeta({
  title: 'Bosca — One platform for content, audience, and delivery',
  description: 'Bosca unifies your content, your work, and your audience in one platform — with analytics, built-in AI agents, recommendations that learn, and experiments that prove what works.'
})

// Subsystem categories drive the platform section; the "platform" category is
// guides (Studio manual, developer docs, Helm), not product surface.
const capabilityCategories = groupSections(SECTIONS).filter(c => c.id !== 'platform')

// The hero constellation: subsystem-accented cubes orbiting the Bosca mark.
// Colors come straight from the section definitions so the visual stays true
// to the product. The bob is JS-driven (see the scene loop below) rather
// than CSS so the link lines and packets can track the boxes exactly —
// `dur`/`phase` are seconds into a shared clock.
const pick = (id: string, fallback: string) => SECTIONS.find(s => s.id === id)?.accent ?? fallback
const ORBIT_CUBES = [
  { color: pick('cms', '#4a8cff'), size: 64, x: '4%', y: '12%', dur: 9, phase: 0 },
  { color: pick('workops', '#7c3aed'), size: 44, x: '82%', y: '6%', dur: 11, phase: 3 },
  { color: pick('analytics', '#22d3ee'), size: 36, x: '90%', y: '58%', dur: 8, phase: 5 },
  { color: pick('audience', '#ec4899'), size: 30, x: '12%', y: '74%', dur: 10, phase: 2 },
  { color: pick('ai', '#a855f7'), size: 50, x: '70%', y: '82%', dur: 12, phase: 7 },
  { color: pick('git', '#64748b'), size: 26, x: '38%', y: '2%', dur: 9.5, phase: 4 },
  { color: pick('bml', '#38bdf8'), size: 34, x: '-2%', y: '44%', dur: 10.5, phase: 6 },
  { color: pick('commerce', '#a670f4'), size: 24, x: '96%', y: '30%', dur: 8.5, phase: 1 }
]

// Straight information links: every box connects to every other box and to
// the core mark, center to center (9 nodes, 36 lines). Endpoints follow the
// boxes — the scene loop rewrites line y-coordinates every frame from the
// same bob math that moves the cubes. Runs through the middle pass behind
// the core mark.
const LINK_VIEW = 440
const r1 = (n: number) => Math.round(n * 10) / 10
const cubeCenter = (cube: (typeof ORBIT_CUBES)[number]) => ({
  x: r1((parseFloat(cube.x) / 100) * LINK_VIEW + cube.size / 2),
  y: r1((parseFloat(cube.y) / 100) * LINK_VIEW + cube.size / 2)
})
const CORE_NODE = { x: LINK_VIEW / 2, y: LINK_VIEW / 2 }
const NODES = [...ORBIT_CUBES.map(cubeCenter), CORE_NODE]
// Node motion table: the eight cubes plus the core mark (index 8, the CSS
// bob it used to have was 7s).
const NODE_MOTION = [...ORBIT_CUBES.map(c => ({ dur: c.dur, phase: c.phase })), { dur: 7, phase: 0 }]
const LINKS = NODES.flatMap((a, i) =>
  NODES.slice(i + 1).map((b, j) => ({ a: i, b: i + 1 + j, x1: a.x, y1: a.y, x2: b.x, y2: b.y }))
)

// Same shape the CSS bob keyframes had: 0 → -14px → 0, eased; here as a
// cosine so it is cheap to evaluate at any scene-time.
const BOB_PX = 14
const bobOffset = (t: number, dur: number, phase: number) =>
  -BOB_PX * (1 - Math.cos((2 * Math.PI * (t + phase)) / dur)) / 2

// Packets ride the links between any two nodes, tinted by the node they
// depart from (departing the core = platform green). Positions are computed
// by the scene loop so packets stay glued to their moving lines.
const CORE_INDEX = NODES.length - 1
const packetDef = (a: number, b: number, dur: number, phase: number, reverse = false) => ({
  a,
  b,
  color: (reverse ? ORBIT_CUBES[b] : ORBIT_CUBES[a])?.color ?? '#00dc82',
  dur,
  phase,
  reverse
})

// Two circuits: every subsystem trades with the core, and traffic circulates
// the outer ring of neighbor links (cube order around the perimeter).
const PERIMETER_RING: [number, number][] = [[5, 1], [1, 7], [7, 2], [2, 4], [4, 3], [3, 6], [6, 0], [0, 5]]
const LINK_PACKETS = [
  ...ORBIT_CUBES.map((_, i) => packetDef(i, CORE_INDEX, 3.6 + (i % 4) * 0.9, i * 1.1, i % 2 === 1)),
  ...PERIMETER_RING.map(([a, b], i) => packetDef(a, b, 4.4 + (i % 3) * 1.2, i * 0.8 + 0.5, i % 3 === 1))
]

const packetPos = (p: (typeof LINK_PACKETS)[number], t: number, offsets: number[]) => {
  const a = NODES[p.a] ?? CORE_NODE
  const b = NODES[p.b] ?? CORE_NODE
  const ay = a.y + (offsets[p.a] ?? 0)
  const by = b.y + (offsets[p.b] ?? 0)
  let k = ((t + p.phase) / p.dur) % 1
  if (p.reverse) k = 1 - k
  return { x: r1(a.x + (b.x - a.x) * k), y: r1(ay + (by - ay) * k) }
}

// SSR-visible starting positions (scene-time 0, unscaled) so packets never
// flash at the SVG origin before the first animation frame.
const packetStart = (p: (typeof LINK_PACKETS)[number]) => {
  const offsets = NODE_MOTION.map(m => bobOffset(0, m.dur, m.phase))
  const pos = packetPos(p, 0, offsets)
  return `translate(${pos.x} ${pos.y})`
}

// The scene loop: one clock moves cubes, mark, line endpoints, and packets,
// so nothing can desync. Cube/mark transforms are physical px; SVG values
// divide by the container scale since the 440-unit viewBox shrinks with the
// layout while the bob amplitude does not.
const constellation = ref<HTMLElement | null>(null)
let sceneFrame = 0
onMounted(() => {
  const root = constellation.value
  if (!root || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const cubes = Array.from(root.querySelectorAll<HTMLElement>('.orbit-cube'))
  const mark = root.querySelector<HTMLElement>('.core-mark')
  const lines = Array.from(root.querySelectorAll<SVGLineElement>('.link-line'))
  const packets = Array.from(root.querySelectorAll<SVGGElement>('.link-packet'))
  const started = performance.now()
  const tick = (now: number) => {
    const t = (now - started) / 1000
    const scale = LINK_VIEW / (root.clientWidth || LINK_VIEW)
    const px = NODE_MOTION.map(m => bobOffset(t, m.dur, m.phase))
    const offsets = px.map(o => o * scale)
    cubes.forEach((el, i) => {
      el.style.transform = `translateY(${px[i] ?? 0}px)`
    })
    if (mark) {
      mark.style.transform = `translateY(${px[NODE_MOTION.length - 1] ?? 0}px)`
    }
    lines.forEach((el, i) => {
      const link = LINKS[i]
      if (!link) return
      el.setAttribute('y1', String(r1(link.y1 + (offsets[link.a] ?? 0))))
      el.setAttribute('y2', String(r1(link.y2 + (offsets[link.b] ?? 0))))
    })
    packets.forEach((el, i) => {
      const p = LINK_PACKETS[i]
      if (!p) return
      const pos = packetPos(p, t, offsets)
      el.setAttribute('transform', `translate(${pos.x} ${pos.y})`)
    })
    sceneFrame = requestAnimationFrame(tick)
  }
  sceneFrame = requestAnimationFrame(tick)
})
onUnmounted(() => cancelAnimationFrame(sceneFrame))

interface GroupNarrative {
  title: string
  body: string
  /** Section id whose accent colors the group's copy; defaults to the first member. */
  accentOf?: string
}

// Narrative copy for each subsystem category. Membership, labels, icons, and
// links all come from useSections so the platform section can never drift from
// the real product surface — only the storytelling lives here, keyed by
// SECTION_CATEGORIES id.
const GROUP_NARRATIVES: Record<string, GroupNarrative> = {
  content: {
    title: 'A content engine, not just a CMS',
    body: 'Collaborative documents, media, guides, and data records with states, transitions, and publishing built in. Ingest external feeds, localize into any language, schedule events on shared calendars, work with Scripture — or render whole sites server-side with BML.'
  },
  audience: {
    title: 'Know your people, and reach them',
    body: 'Profiles and organizations feed segments and campaigns. Chat channels, email, and notifications carry your message out, and forms bring responses back in — all tied to the same profile record.'
  },
  measure: {
    title: 'Understand what works, then act on it',
    body: 'Product analytics and error tracking show what people actually do. Experiments and feature flags measure what your changes really did. Recommendations learn from all of it to personalize what each person sees.'
  },
  build: {
    title: 'Where the work and the code live together',
    body: 'Specs, requirements, sprints, and boards sit next to your repositories — self-hosted Git with pull requests, reviews, CI pipelines, and an artifact registry. Kotlin scripts and event-driven pipelines automate the platform, and AI agents work across every subsystem.'
  },
  commerce: {
    title: 'Sell from the platform you publish from',
    body: 'Stores, catalogs, products, and promotions; carts, orders, returns, and fulfillment; subscriptions and billing. Commerce shares the same content, audience, and analytics as the rest of the platform.'
  },
  operate: {
    title: 'Your infrastructure, your data',
    body: 'A gateway proxies authenticated traffic to internal services, a built-in Kubernetes console manages your clusters, and system tools cover jobs, security, storage, and backups.',
    accentOf: 'kubernetes'
  },
  administer: {
    title: 'One app to run all of it',
    body: 'Bosca Studio is the admin app for the whole platform — every subsystem behind one sign-in, one search, and one shell, with Studio Personas shaping what each person sees. Install and upgrade it with the official Helm charts.',
    accentOf: 'studio'
  }
}

// Each category renders as a narrative block plus the chips for its member
// subsystems. The group accent tints the copy; every chip keeps its own.
const platformGroups = capabilityCategories.map((cat) => {
  const narrative = GROUP_NARRATIVES[cat.id]
  const accentId = narrative?.accentOf ?? cat.items[0]?.id
  return {
    ...cat,
    narrative,
    accent: pick(accentId ?? '', '#00dc82')
  }
})

// Ambient particles for the platform panels: one fixed, hand-scattered
// layout shared by every panel (they never appear side by side, so reuse
// reads as intentional). The drift vector varies per particle so the field
// shimmers instead of sliding in one direction.
const PANEL_PARTICLES = [
  { x: '16%', y: '24%', size: 4, dx: '10px', dy: '-14px', dur: '7s', delay: '0s' },
  { x: '76%', y: '16%', size: 3, dx: '-8px', dy: '12px', dur: '9s', delay: '-3s' },
  { x: '86%', y: '46%', size: 5, dx: '-12px', dy: '-10px', dur: '8s', delay: '-5s' },
  { x: '70%', y: '80%', size: 3, dx: '9px', dy: '-12px', dur: '10s', delay: '-2s' },
  { x: '26%', y: '84%', size: 4, dx: '12px', dy: '10px', dur: '8.5s', delay: '-6s' },
  { x: '10%', y: '58%', size: 3, dx: '-9px', dy: '-13px', dur: '9.5s', delay: '-4s' },
  { x: '44%', y: '10%', size: 3, dx: '8px', dy: '11px', dur: '7.5s', delay: '-1s' },
  { x: '60%', y: '64%', size: 2, dx: '-10px', dy: '12px', dur: '11s', delay: '-7s' }
]

// Each particle takes one of the group's member subsystem accents, cycling
// through them, so a panel's field is comprised of all its subsystem colors.
const particleColor = (group: (typeof platformGroups)[number], index: number) =>
  group.items[index % Math.max(group.items.length, 1)]?.accent ?? group.accent

const DEV_POINTS = [
  { title: 'GraphQL-first', body: 'Every subsystem speaks one schema. Typed clients, subscriptions, and batching included.' },
  { title: 'A robust CLI', body: 'A single native binary drives content, work, CI, and deployment — and embeds an MCP server for AI assistants.' },
  { title: 'Self-hostable', body: 'Official Helm charts, a built-in Kubernetes console, and PostgreSQL at the core. Your infrastructure, your data.' },
  { title: 'Scriptable everywhere', body: 'Kotlin scripts, visual pipelines, and event triggers automate the platform from the inside.' }
]

// Scroll-triggered reveals. Reduced motion shows everything immediately.
let observer: IntersectionObserver | null = null
onMounted(() => {
  const els = document.querySelectorAll<HTMLElement>('.reveal')
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    els.forEach(el => el.classList.add('is-visible'))
    return
  }
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible')
        observer?.unobserve(entry.target)
      }
    }
  }, { threshold: 0.15 })
  els.forEach(el => observer?.observe(el))
})
onUnmounted(() => observer?.disconnect())
</script>

<template>
  <div
    class="landing"
    data-theme="dark"
  >
    <div class="atmosphere" />
    <div class="iso-lattice" />
    <div class="grain" />

    <!-- ── Nav ─────────────────────────────────── -->
    <header class="landing-nav">
      <NuxtLink
        to="/"
        class="brand"
      >
        <BoscaMark :size="24" />
        <span>Bosca</span>
      </NuxtLink>
      <nav class="nav-links">
        <a href="#platform">Platform</a>
        <a href="#developers">Developers</a>
      </nav>
      <a
        href="https://github.com/bosca-io/bosca"
        class="github-link"
        target="_blank"
        rel="noopener noreferrer"
        aria-label="GitHub"
        title="GitHub"
      >
        <GitHubIcon />
      </a>
    </header>

    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Early access
        </p>
        <h1 class="load-2">
          Content, audience,<br>
          and delivery.<br>
          <em>One platform.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca unifies your content, your work, and your audience — then puts
          intelligence to work on all of it: analytics across the whole
          platform, built-in AI agents, recommendations that learn what each
          person cares about, and experiments that prove what works.
        </p>
        <div class="hero-ctas load-4">
          <a
            href="#platform"
            class="btn btn-primary"
          >
            Explore the platform
          </a>
          <a
            href="#developers"
            class="btn btn-secondary"
          >
            For developers
          </a>
        </div>
      </div>

      <div
        class="hero-visual load-4"
        aria-hidden="true"
      >
        <div
          ref="constellation"
          class="constellation"
        >
          <div class="core-glow" />
          <svg
            class="link-layer"
            :viewBox="`0 0 ${LINK_VIEW} ${LINK_VIEW}`"
            fill="none"
          >
            <line
              v-for="(link, i) in LINKS"
              :key="`link-${i}`"
              :x1="link.x1"
              :y1="link.y1"
              :x2="link.x2"
              :y2="link.y2"
              class="link-line"
              pathLength="1"
              :style="{ animationDelay: `${0.35 + i * 0.03}s` }"
            />
            <g
              v-for="(packet, i) in LINK_PACKETS"
              :key="`packet-${i}`"
              class="link-packet"
              :transform="packetStart(packet)"
            >
              <circle
                r="4.5"
                :fill="packet.color"
                opacity="0.3"
              />
              <circle
                r="1.7"
                fill="#e6fff4"
              />
            </g>
          </svg>
          <BoscaMark
            :size="190"
            class="core-mark"
          />
          <div
            v-for="(cube, i) in ORBIT_CUBES"
            :key="i"
            class="orbit-cube"
            :style="{
              '--cube-x': cube.x,
              '--cube-y': cube.y
            }"
          >
            <BoscaMark
              :size="cube.size"
              :color="cube.color"
            />
          </div>
        </div>
      </div>
    </section>

    <!-- ── Platform ────────────────────────────── -->
    <section
      id="platform"
      class="capabilities"
    >
      <div class="plat-groups">
        <article
          v-for="(group, i) in platformGroups"
          :key="group.id"
          class="plat-group reveal"
          :class="{ flipped: i % 2 === 1 }"
          :style="{ '--group-accent': group.accent }"
        >
          <div class="plat-head">
            <div class="plat-copy">
              <p class="kicker plat-kicker">
                {{ group.label }}
              </p>
              <h3>{{ group.narrative?.title ?? group.label }}</h3>
              <p
                v-if="group.narrative"
                class="plat-body"
              >
                {{ group.narrative.body }}
              </p>
            </div>
            <div
              class="plat-panel"
              aria-hidden="true"
            >
              <div
                v-for="(particle, pi) in PANEL_PARTICLES"
                :key="`particle-${pi}`"
                class="panel-particle"
                :style="{
                  '--particle-x': particle.x,
                  '--particle-y': particle.y,
                  '--particle-size': `${particle.size}px`,
                  '--particle-dx': particle.dx,
                  '--particle-dy': particle.dy,
                  '--particle-dur': particle.dur,
                  '--particle-delay': particle.delay,
                  '--particle-color': particleColor(group, pi)
                }"
              />
              <div class="panel-mark">
                <BoscaMark
                  :size="96"
                  :color="group.accent"
                />
              </div>
            </div>
          </div>
          <div class="plat-chips">
            <component
              :is="section.discover ? NuxtLink : (opensLearnMore(section.id) ? 'button' : 'div')"
              v-for="section in group.items"
              :key="section.id"
              :to="section.discover"
              :type="opensLearnMore(section.id) ? 'button' : undefined"
              class="cap-chip"
              :class="{ 'cap-chip-link': section.discover || opensLearnMore(section.id) }"
              :style="{ '--chip-accent': section.accent }"
              @click="opensLearnMore(section.id) && (learnMore = section.accent)"
            >
              <span class="cap-icon">
                <Icon
                  :name="section.icon"
                  :size="15"
                />
              </span>
              <span class="cap-text">
                <strong>{{ section.label }}</strong>
                <small>{{ section.sub }}</small>
              </span>
              <Icon
                v-if="section.discover || opensLearnMore(section.id)"
                name="arrow-up-right"
                :size="14"
                class="cap-arrow"
              />
            </component>
          </div>
        </article>
      </div>
    </section>

    <!-- ── Developers ──────────────────────────── -->
    <section
      id="developers"
      class="developers"
    >
      <div class="dev-inner reveal">
        <div class="dev-copy">
          <p class="kicker">
            For developers
          </p>
          <h2>One schema to <em>everything</em></h2>
          <p class="section-sub">
            Every capability — content, work, audience, infrastructure — hangs
            off a single GraphQL schema with real-time subscriptions. If you can
            see it in the app, you can query it.
          </p>
          <dl class="dev-points">
            <div
              v-for="point in DEV_POINTS"
              :key="point.title"
            >
              <dt>{{ point.title }}</dt>
              <dd>{{ point.body }}</dd>
            </div>
          </dl>
          <NuxtLink
            to="/discover/developers"
            class="btn btn-primary dev-cta"
          >
            Build on Bosca
          </NuxtLink>
        </div>
        <div class="dev-code">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">query.graphql</span>
          </div>
          <pre><code><span class="tok-kw">query</span> {
  <span class="tok-field">content</span> {
    <span class="tok-field">categories</span> {
      <span class="tok-field">all</span> {
        <span class="tok-attr">id</span>
        <span class="tok-attr">name</span>
      }
    }
  }
  <span class="tok-field">workops</span> {
    <span class="tok-field">tasks</span> {
      <span class="tok-field">taskByKey</span>(<span class="tok-arg">key</span>: <span class="tok-str">"PLAT-42"</span>) {
        <span class="tok-attr">key</span>
        <span class="tok-attr">summary</span>
      }
    }
  }
}</code></pre>
        </div>
      </div>
    </section>

    <!-- ── Closing CTA ─────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        class="closing-mark"
      />
      <h2>Bosca is in <em>early access</em></h2>
      <p class="section-sub">
        We're opening the platform gradually — content, audience, delivery, and
        everything that connects them.
      </p>
      <a
        href="#platform"
        class="btn btn-primary btn-lg"
      >
        Explore the platform
      </a>
    </section>

    <LearnMoreModal
      v-if="learnMore"
      :accent="learnMore"
      @close="learnMore = null"
    />

    <!-- ── Footer ──────────────────────────────── -->
    <footer class="landing-footer">
      <div class="foot-left">
        <NuxtLink
          to="/"
          class="foot-brand"
        >
          <BoscaMark :size="18" />
          <span>Bosca</span>
        </NuxtLink>
      </div>
      <nav
        class="foot-links"
      >
        <a
          href="https://github.com/bosca-io/bosca"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="GitHub"
          title="GitHub"
        >
          <GitHubIcon />
        </a>
        <NuxtLink
          v-for="link in legalLinks"
          :key="link.label"
          :to="link.url"
          external
        >
          {{ link.label }}
        </NuxtLink>
      </nav>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  --green: #00dc82;
  --green-deep: #00a155;
  --font-display: 'Instrument Serif', Georgia, serif;
  position: relative;
  min-height: 100vh;
  background: var(--bg-0);
  color: var(--fg-0);
  font-family: var(--font-sans);
  overflow-x: clip;
}

/* ── Atmosphere layers ───────────────────────── */

.atmosphere {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(70% 50% at 78% 8%, color-mix(in oklch, var(--green) 16%, transparent), transparent 62%),
    radial-gradient(50% 40% at 8% 30%, color-mix(in oklch, var(--green-deep) 10%, transparent), transparent 65%),
    radial-gradient(80% 45% at 50% 105%, color-mix(in oklch, var(--green) 8%, transparent), transparent 70%);
  pointer-events: none;
}

/* Faint isometric lattice echoing the cube mark's 30° geometry. */
.iso-lattice {
  position: absolute;
  inset: 0 0 auto;
  height: 130vh;
  background:
    repeating-linear-gradient(60deg, color-mix(in srgb, var(--green) 5%, transparent) 0 1px, transparent 1px 72px),
    repeating-linear-gradient(-60deg, color-mix(in srgb, var(--green) 5%, transparent) 0 1px, transparent 1px 72px);
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.9), transparent 85%);
  pointer-events: none;
}

.grain {
  position: fixed;
  inset: 0;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.5'/%3E%3C/svg%3E");
  opacity: 0.035;
  pointer-events: none;
  z-index: 1;
}

.landing > section,
.landing-nav,
.landing-footer {
  position: relative;
  z-index: 2;
}

/* ── Shared type & buttons ───────────────────── */

.kicker {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  color: var(--green);
  margin-bottom: 14px;
}

h1, h2 {
  letter-spacing: -0.03em;
  line-height: 1.06;
  margin: 0;
}

h1 em, h2 em {
  font-family: var(--font-display);
  font-style: italic;
  font-weight: 400;
  letter-spacing: -0.01em;
  color: var(--green);
}

.section-sub {
  font-size: 16px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 520px;
  margin: 18px 0 0;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 10px 22px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  border: 1px solid transparent;
  transition: all 0.2s ease;
}

.btn-primary {
  background: var(--green);
  color: #04150c;
}

.btn-primary:hover {
  background: #33e59b;
  box-shadow: 0 6px 28px color-mix(in srgb, var(--green) 35%, transparent);
  transform: translateY(-1px);
}

.btn-secondary {
  color: var(--fg-0);
  border-color: var(--line-2);
}

.btn-secondary:hover {
  border-color: var(--green);
  color: var(--green);
}

.btn-ghost {
  color: var(--fg-1);
  border-color: var(--line);
  padding: 7px 16px;
  font-size: 13px;
}

.btn-ghost:hover {
  color: var(--green);
  border-color: color-mix(in srgb, var(--green) 45%, transparent);
}

.btn-lg {
  padding: 13px 34px;
  font-size: 15px;
}

/* ── Nav ─────────────────────────────────────── */

.landing-nav {
  display: flex;
  align-items: center;
  gap: 32px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 22px 32px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: var(--fg-0);
  font-weight: 700;
  font-size: 16px;
  letter-spacing: -0.01em;
}

.nav-links {
  display: flex;
  gap: 26px;
  margin-left: auto;
}

.github-link {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
}

.nav-links + .github-link {
  margin-left: 0;
}

.nav-links a,
.github-link {
  font-size: 13.5px;
  color: var(--fg-2);
  text-decoration: none;
  transition: color 0.15s ease;
}

.nav-links a:hover,
.github-link:hover {
  color: var(--fg-0);
}

/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  align-items: center;
  gap: 40px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 72px 32px 110px;
}

.hero h1 {
  font-size: clamp(44px, 6vw, 72px);
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
  color: var(--green);
  border: 1px solid color-mix(in srgb, var(--green) 30%, transparent);
  border-radius: 999px;
  padding: 6px 14px;
  margin-bottom: 26px;
}

.eyebrow-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--green);
  box-shadow: 0 0 10px var(--green);
  animation: pulse 2.4s ease-in-out infinite;
}

.hero-sub {
  font-size: 17px;
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

/* Staggered load-in for the hero. */
.load-1, .load-2, .load-3, .load-4 {
  opacity: 0;
  transform: translateY(14px);
  animation: rise 0.7s cubic-bezier(0.2, 0.6, 0.2, 1) forwards;
}

.load-2 { animation-delay: 0.1s; }
.load-3 { animation-delay: 0.2s; }
.load-4 { animation-delay: 0.32s; }

/* ── Constellation ───────────────────────────── */

.hero-visual {
  display: flex;
  justify-content: center;
}

.constellation {
  position: relative;
  width: min(440px, 100%);
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.core-glow {
  position: absolute;
  width: 62%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, var(--green) 22%, transparent), transparent 70%);
  filter: blur(6px);
}

/* Hero motion (mark, cubes, lines, packets) is driven by the JS scene loop
   so everything stays in lockstep — no CSS bob here. */
.core-mark {
  position: relative;
  filter: drop-shadow(0 18px 50px color-mix(in srgb, var(--green) 30%, transparent));
}

/* The straight-link mesh. The layer sits between the core glow and the
   mark/cubes, so line ends tuck under the glyphs and runs through the middle
   pass behind the core. */
.link-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
}

/* pathLength="1" normalizes every line so a single dasharray/offset pair
   draws each one in, regardless of its real length. */
.link-line {
  stroke: color-mix(in srgb, var(--green) 16%, transparent);
  stroke-width: 1;
  stroke-linecap: round;
  stroke-dasharray: 1;
  stroke-dashoffset: 1;
  animation: link-draw 1.2s cubic-bezier(0.2, 0.6, 0.2, 1) forwards;
}

@keyframes link-draw {
  to { stroke-dashoffset: 0; }
}

.orbit-cube {
  position: absolute;
  left: var(--cube-x);
  top: var(--cube-y);
  opacity: 0.9;
}

.orbit-cube :deep(svg) {
  filter: drop-shadow(0 10px 24px rgba(0, 0, 0, 0.45));
}

@keyframes bob {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-14px); }
}

@keyframes rise {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

/* ── Platform ────────────────────────────────── */

.capabilities {
  max-width: 1140px;
  margin: 0 auto;
  padding: 40px 32px 110px;
}

.plat-groups {
  display: flex;
  flex-direction: column;
  gap: 110px;
}

.plat-head {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
  gap: 56px;
  align-items: center;
  margin-bottom: 28px;
}

.plat-group.flipped .plat-copy {
  order: 2;
}

.plat-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  height: 100%;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background:
    radial-gradient(70% 70% at 50% 40%, color-mix(in srgb, var(--group-accent) 12%, transparent), transparent 75%),
    color-mix(in srgb, var(--bg-1) 60%, transparent);
  overflow: hidden;
  position: relative;
}

.plat-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    repeating-linear-gradient(60deg, color-mix(in srgb, var(--group-accent) 7%, transparent) 0 1px, transparent 1px 46px),
    repeating-linear-gradient(-60deg, color-mix(in srgb, var(--group-accent) 7%, transparent) 0 1px, transparent 1px 46px);
  mask-image: radial-gradient(80% 80% at 50% 50%, rgba(0, 0, 0, 0.9), transparent);
}

.panel-mark {
  position: relative;
  animation: bob 8s ease-in-out infinite;
}

/* Ambient particles drifting around each panel's mark, colored by the
   group's member subsystems. */
.panel-particle {
  position: absolute;
  left: var(--particle-x);
  top: var(--particle-y);
  width: var(--particle-size);
  height: var(--particle-size);
  border-radius: 999px;
  background: var(--particle-color);
  box-shadow: 0 0 8px color-mix(in srgb, var(--particle-color) 55%, transparent);
  opacity: 0.25;
  animation: particle-drift var(--particle-dur) ease-in-out var(--particle-delay) infinite;
}

@keyframes particle-drift {
  0%, 100% {
    transform: translate(0, 0);
    opacity: 0.25;
  }

  50% {
    transform: translate(var(--particle-dx), var(--particle-dy));
    opacity: 0.7;
  }
}

.plat-copy h3 {
  font-size: clamp(24px, 3vw, 32px);
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.12;
  margin: 0;
}

.plat-kicker {
  color: var(--group-accent);
}

.plat-body {
  font-size: 15px;
  line-height: 1.75;
  color: var(--fg-2);
  margin: 16px 0 0;
}

/* minmax(0, 1fr) keeps every track identical — a plain 1fr track's auto
   minimum lets long nowrap descriptions stretch individual columns. */
.plat-chips {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.cap-chip {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.cap-chip:hover {
  border-color: color-mix(in srgb, var(--chip-accent) 55%, transparent);
  transform: translateY(-2px);
}

/* A chip that opens the learn-more form is a <button>; strip the UA defaults
   so it sits identically beside the link and div variants. */
button.cap-chip {
  width: 100%;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

/* Chips that link to a /discover marketing page read as clickable: a tinted
   border at rest and an arrow that brightens on hover. */
.cap-chip-link {
  text-decoration: none;
  color: inherit;
  border-color: color-mix(in srgb, var(--chip-accent) 28%, var(--line));
}

.cap-chip-link:hover {
  background: color-mix(in srgb, var(--chip-accent) 8%, var(--bg-1));
}

.cap-arrow {
  flex-shrink: 0;
  color: var(--chip-accent);
  opacity: 0.55;
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.cap-chip-link:hover .cap-arrow {
  opacity: 1;
  transform: translate(1px, -1px);
}

.cap-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: color-mix(in srgb, var(--chip-accent) 14%, transparent);
  color: var(--chip-accent);
}

.cap-text {
  display: block;
  flex: 1;
  min-width: 0;
}

.cap-text strong {
  display: block;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.cap-text small {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 11.5px;
  line-height: 1.45;
  color: var(--fg-3);
}

/* ── Developers ──────────────────────────────── */

.developers {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 45%, transparent);
}

.dev-inner {
  max-width: 1140px;
  margin: 0 auto;
  padding: 90px 32px;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 60px;
  align-items: start;
}

.dev-copy h2 {
  font-size: clamp(28px, 3.6vw, 40px);
  font-weight: 700;
}

.dev-points {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 26px 30px;
  margin: 36px 0 0;
}

.dev-points dt {
  font-size: 14px;
  font-weight: 650;
  color: var(--fg-0);
  margin-bottom: 6px;
}

.dev-points dd {
  font-size: 13px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

.dev-cta {
  margin-top: 32px;
}

.dev-code {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.code-chrome {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-1);
}

.code-chrome .dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--bg-4);
}

.code-title {
  margin-left: 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.dev-code pre {
  margin: 0;
  padding: 18px 20px;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.7;
  color: var(--fg-1);
  overflow-x: auto;
}

.tok-kw { color: var(--green); }
.tok-field { color: #5ec5ff; }
.tok-attr { color: var(--fg-1); }
.tok-arg { color: #ffb547; }
.tok-str { color: #34d99a; }

/* ── Closing ─────────────────────────────────── */

.closing {
  max-width: 640px;
  margin: 0 auto;
  padding: 110px 32px;
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
  animation: bob 7s ease-in-out infinite;
}

/* ── Footer ──────────────────────────────────── */

.landing-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 14px 24px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 28px 32px 40px;
  border-top: 1px solid var(--line);
}

.foot-left {
  display: flex;
  align-items: center;
  gap: 18px;
}

.foot-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-2);
  text-decoration: none;
  transition: color 0.15s ease;
}

.foot-brand:hover {
  color: var(--fg-0);
}

.foot-links {
  display: flex;
  align-items: center;
  gap: 22px;
}

.foot-links a {
  font-size: 13px;
  color: var(--fg-3);
  text-decoration: none;
  transition: color 0.15s ease;
}

.foot-links a:hover {
  color: var(--fg-0);
}

/* ── Reveal on scroll ────────────────────────── */

.reveal {
  opacity: 0;
  transform: translateY(22px);
  transition: opacity 0.7s cubic-bezier(0.2, 0.6, 0.2, 1), transform 0.7s cubic-bezier(0.2, 0.6, 0.2, 1);
}

.reveal.is-visible {
  opacity: 1;
  transform: translateY(0);
}

/* ── Motion & responsive ─────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .load-1, .load-2, .load-3, .load-4 {
    animation: none;
    opacity: 1;
    transform: none;
  }

  .panel-mark, .closing-mark, .eyebrow-dot, .panel-particle {
    animation: none;
  }

  /* SMIL ignores this media query, so the traveling packets are hidden
     outright; the static link mesh remains, fully drawn. */
  .link-line {
    animation: none;
    stroke-dashoffset: 0;
  }

  .link-packet {
    display: none;
  }

  .reveal {
    transition: none;
    opacity: 1;
    transform: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: minmax(0, 1fr);
    padding-top: 40px;
    padding-bottom: 70px;
  }

  .hero-visual {
    order: -1;
  }

  .constellation {
    width: min(320px, 80vw);
  }

  .dev-inner {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }

  .plat-head {
    grid-template-columns: minmax(0, 1fr);
    gap: 24px;
  }

  .plat-group.flipped .plat-copy {
    order: 0;
  }

  .plat-panel {
    min-height: 160px;
  }

  .plat-groups {
    gap: 72px;
  }

  .plat-chips {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .nav-links {
    display: none;
  }

  .nav-links + .github-link {
    margin-left: auto;
  }

  .landing-nav .btn-ghost {
    margin-left: auto;
  }
}

@media (max-width: 640px) {
  .plat-chips {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
