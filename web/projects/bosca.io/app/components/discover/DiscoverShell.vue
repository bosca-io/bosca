<script setup lang="ts">
import { SECTIONS } from '~/composables/useSections'

// The chrome shared by every /discover marketing page: atmosphere layers, nav,
// footer, scroll reveals, and the shared design vocabulary (styles below are
// deliberately unscoped — scoped CSS cannot reach slotted page content — and
// every rule is prefixed with .discover to keep them contained).
const props = withDefaults(defineProps<{
  /** Section id from the SECTIONS registry — names the family and sources its accent. */
  sectionId: string
  links?: { label: string, href: string }[]
}>(), {
  links: () => []
})

// Each family pairs the registry accent with a hand-picked deeper companion
// used only in the atmosphere gradients.
const DEEP_ACCENTS: Record<string, string> = {
  bml: '#0284c7',
  pipelines: '#059669',
  cms: '#e0455c',
  recommendations: '#4d9a1f',
  analytics: '#1c7a5e',
  git: '#475569',
  audience: '#b07d1c',
  experiments: '#8e1f9b',
  communications: '#1857c9',
  bible: '#b45309',
  localization: '#2fa028',
  scripts: '#227894',
  commerce: '#7f3ae8',
  artifacts: '#c026d3',
  workops: '#5b6fd6',
  forms: '#c9247e',
  gateway: '#98a12d',
  feeds: '#0891b2',
  calendar: '#5b21b6',
  ai: '#e0673a',
  system: '#b91c1c',
  kubernetes: '#2f5fd0',
  developers: '#1d4ed8',
  studio: '#00a15e'
}

// Single-source the accent from the section registry so the marketing pages
// can never drift from the docs section's color.
const section = computed(() => SECTIONS.find(s => s.id === props.sectionId))
const accent = computed(() => section.value?.accent ?? '#38bdf8')
const accentDeep = computed(() => DEEP_ACCENTS[props.sectionId] ?? accent.value)
const label = computed(() => section.value?.label ?? props.sectionId)
const discoverPath = computed(() => `/discover/${props.sectionId}`)
const legalLinks = useLegalLinks()

const root = ref<HTMLElement | null>(null)

// Scroll-triggered reveals for slotted content. Reduced motion shows
// everything immediately.
let observer: IntersectionObserver | null = null
onMounted(() => {
  const els = root.value?.querySelectorAll<HTMLElement>('.reveal') ?? []
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
    ref="root"
    class="discover"
    data-theme="dark"
    :style="{ '--accent': accent, '--accent-deep': accentDeep }"
  >
    <div class="atmosphere" />
    <div class="iso-lattice" />
    <div class="grain" />

    <header class="discover-nav">
      <NuxtLink
        to="/"
        class="brand"
      >
        <BoscaMark :size="24" />
        <span>Bosca</span>
      </NuxtLink>
      <NuxtLink
        :to="discoverPath"
        class="brand-sub"
      >
        / {{ label }}
      </NuxtLink>
      <nav
        v-if="links.length"
        class="nav-links"
      >
        <a
          v-for="link in links"
          :key="link.href"
          :href="link.href"
        >{{ link.label }}</a>
      </nav>
      <NuxtLink
        v-if="sectionId === 'bml'"
        to="/bml-reference/getting-started"
        class="learning-guide-link"
      >
        BML Reference
      </NuxtLink>
      <NuxtLink
        v-if="sectionId === 'recommendations'"
        to="/recommendation-models/start-here"
        class="learning-guide-link"
      >
        Build the Models
      </NuxtLink>
    </header>

    <slot />

    <footer class="discover-footer">
      <div class="foot-left">
        <NuxtLink
          to="/"
          class="foot-brand"
        >
          <BoscaMark :size="18" />
          <span>Bosca</span>
        </NuxtLink>
      </div>
      <nav class="foot-links">
        <NuxtLink :to="discoverPath">{{ label }}</NuxtLink>
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

<style>
.discover {
  --font-display: 'Instrument Serif', Georgia, serif;
  --code-lh: 22px;
  --code-pad-y: 16px;
  position: relative;
  min-height: 100vh;
  background: var(--bg-0);
  color: var(--fg-0);
  font-family: var(--font-sans);
  overflow-x: clip;
}

/* ── Atmosphere layers ───────────────────────── */

.discover .atmosphere {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(70% 50% at 78% 8%, color-mix(in oklch, var(--accent) 16%, transparent), transparent 62%),
    radial-gradient(50% 40% at 8% 30%, color-mix(in oklch, var(--accent-deep) 10%, transparent), transparent 65%),
    radial-gradient(80% 45% at 50% 105%, color-mix(in oklch, var(--accent) 8%, transparent), transparent 70%);
  pointer-events: none;
}

/* Faint isometric lattice echoing the cube mark's 30° geometry. */
.discover .iso-lattice {
  position: absolute;
  inset: 0 0 auto;
  height: 130vh;
  background:
    repeating-linear-gradient(60deg, color-mix(in srgb, var(--accent) 5%, transparent) 0 1px, transparent 1px 72px),
    repeating-linear-gradient(-60deg, color-mix(in srgb, var(--accent) 5%, transparent) 0 1px, transparent 1px 72px);
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.9), transparent 85%);
  pointer-events: none;
}

.discover .grain {
  position: fixed;
  inset: 0;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.5'/%3E%3C/svg%3E");
  opacity: 0.035;
  pointer-events: none;
  z-index: 1;
}

.discover > section,
.discover .discover-nav,
.discover .discover-footer {
  position: relative;
  z-index: 2;
}

/* ── Shared type & buttons ───────────────────── */

.discover .kicker {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  color: var(--accent);
  margin-bottom: 14px;
}

.discover h1,
.discover h2 {
  letter-spacing: -0.03em;
  line-height: 1.06;
  margin: 0;
}

.discover h1 em,
.discover h2 em {
  font-family: var(--font-display);
  font-style: italic;
  font-weight: 400;
  letter-spacing: -0.01em;
  color: var(--accent);
}

.discover .section-sub {
  font-size: 15.5px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 520px;
  margin: 18px 0 0;
}

.discover .section-sub code,
.discover .point-list code {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.discover .btn {
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

.discover .btn-primary {
  background: var(--accent);
  color: #05131c;
}

.discover .btn-primary:hover {
  background: color-mix(in srgb, var(--accent) 82%, white);
  box-shadow: 0 6px 28px color-mix(in srgb, var(--accent) 35%, transparent);
  transform: translateY(-1px);
}

.discover .btn-secondary {
  color: var(--fg-0);
  border-color: var(--line-2);
}

.discover .btn-secondary:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.discover .btn-ghost {
  color: var(--fg-1);
  border-color: var(--line);
  padding: 7px 16px;
  font-size: 13px;
}

.discover .btn-ghost:hover {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}

/* ── Nav ─────────────────────────────────────── */

.discover .discover-nav {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 22px 32px;
}

.discover .brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: var(--fg-0);
  font-weight: 700;
  font-size: 16px;
  letter-spacing: -0.01em;
}

.discover .brand-sub {
  text-decoration: none;
  color: var(--accent);
  font-weight: 700;
  font-size: 16px;
  letter-spacing: -0.01em;
}

.discover .nav-links {
  display: flex;
  gap: 26px;
  margin-left: auto;
}

.discover .nav-links a {
  font-size: 13.5px;
  color: var(--fg-2);
  text-decoration: none;
  transition: color 0.15s ease;
}

.discover .nav-links a:hover {
  color: var(--fg-0);
}

.discover .learning-guide-link {
  margin-left: auto;
  padding: 7px 14px;
  border: 1px solid color-mix(in srgb, var(--accent) 45%, transparent);
  border-radius: 999px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 650;
  text-decoration: none;
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}

.discover .nav-links + .learning-guide-link {
  margin-left: 2px;
}

.discover .learning-guide-link:hover {
  border-color: var(--accent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  color: var(--fg-0);
}

/* ── Section scaffolding ─────────────────────── */

.discover .section {
  max-width: 1140px;
  margin: 0 auto;
  padding: 30px 32px 90px;
}

.discover .section-head {
  max-width: 640px;
  margin-bottom: 42px;
}

.discover .section-head h2 {
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 700;
}

/* A detail page's opening block. */
.discover .page-hero {
  max-width: 1140px;
  margin: 0 auto;
  padding: 48px 32px 64px;
}

.discover .page-hero h1 {
  font-size: clamp(38px, 5vw, 58px);
  font-weight: 700;
  max-width: 720px;
}

.discover .page-hero .section-sub {
  max-width: 560px;
}

/* Two-column copy + code split. */
.discover .split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
  gap: 56px;
  align-items: center;
}

.discover .split.flipped .split-copy {
  order: 2;
}

.discover .split-copy h2 {
  font-size: clamp(26px, 3.2vw, 36px);
  font-weight: 700;
}

/* Diamond-bulleted fact list, echoing the cube mark. */
.discover .point-list {
  list-style: none;
  padding: 0;
  margin: 22px 0 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.discover .point-list li {
  position: relative;
  padding-left: 20px;
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--fg-1);
}

.discover .point-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  width: 9px;
  height: 9px;
  background: var(--accent);
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
  opacity: 0.9;
}

/* ── Code windows ────────────────────────────── */

.discover .code-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.discover .code-chrome {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-1);
}

.discover .code-chrome .dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--bg-4);
}

.discover .code-title {
  margin-left: 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.discover .code-body {
  position: relative;
}

.discover .code-body pre {
  margin: 0;
  padding: var(--code-pad-y) 20px;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: var(--code-lh);
  color: var(--fg-1);
  overflow-x: auto;
}

.discover .tok-tag { color: var(--accent); }
.discover .tok-attr { color: #ffb547; }
.discover .tok-str { color: #34d99a; }
.discover .tok-interp { color: #e8edf4; font-weight: 600; }
.discover .tok-kt { color: #c4b5fd; }
.discover .tok-com { color: var(--fg-3); }

/* ── Screenshot windows ──────────────────────── */

/* A product screenshot framed like the code windows, so real UI and code
   samples share one visual vocabulary. */
.discover .shot-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 8%, transparent);
}

.discover .shot-window img {
  display: block;
  width: 100%;
  height: auto;
}

.discover .shot-caption {
  margin: 14px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-3);
  text-align: center;
}

/* ── Link cards (Go deeper / Keep exploring) ── */

.discover .explore-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.discover .explore-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  text-decoration: none;
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.discover .explore-card:hover {
  border-color: color-mix(in srgb, var(--accent) 50%, transparent);
  transform: translateY(-2px);
}

.discover .explore-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

.discover .explore-text {
  flex: 1;
  min-width: 0;
}

.discover .explore-text h3 {
  font-size: 14px;
  font-weight: 650;
  color: var(--fg-0);
  margin: 0 0 4px;
}

.discover .explore-text p {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--fg-2);
  margin: 0;
}

.discover .explore-arrow {
  color: var(--fg-3);
  flex-shrink: 0;
}

.discover .explore-card:hover .explore-arrow {
  color: var(--accent);
}

/* ── Footer ──────────────────────────────────── */

.discover .discover-footer {
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

.discover .foot-left {
  display: flex;
  align-items: center;
  gap: 18px;
}

.discover .foot-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-2);
  text-decoration: none;
  transition: color 0.15s ease;
}

.discover .foot-brand:hover {
  color: var(--fg-0);
}

.discover .foot-links {
  display: flex;
  gap: 22px;
}

.discover .foot-links a {
  font-size: 13px;
  color: var(--fg-3);
  text-decoration: none;
  transition: color 0.15s ease;
}

.discover .foot-links a:hover {
  color: var(--fg-0);
}

/* ── Motion ──────────────────────────────────── */

.discover .load-1,
.discover .load-2,
.discover .load-3,
.discover .load-4 {
  opacity: 0;
  transform: translateY(14px);
  animation: discover-rise 0.7s cubic-bezier(0.2, 0.6, 0.2, 1) forwards;
}

.discover .load-2 { animation-delay: 0.1s; }
.discover .load-3 { animation-delay: 0.2s; }
.discover .load-4 { animation-delay: 0.32s; }

.discover .reveal {
  opacity: 0;
  transform: translateY(22px);
  transition: opacity 0.7s cubic-bezier(0.2, 0.6, 0.2, 1), transform 0.7s cubic-bezier(0.2, 0.6, 0.2, 1);
}

.discover .reveal.is-visible {
  opacity: 1;
  transform: translateY(0);
}

@keyframes discover-rise {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes discover-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

@keyframes discover-bob {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-14px); }
}

/* ── Reduced motion & responsive ─────────────── */

@media (prefers-reduced-motion: reduce) {
  .discover .load-1,
  .discover .load-2,
  .discover .load-3,
  .discover .load-4 {
    animation: none;
    opacity: 1;
    transform: none;
  }

  .discover .reveal {
    transition: none;
    opacity: 1;
    transform: none;
  }
}

@media (max-width: 960px) {
  .discover .nav-links {
    display: none;
  }

  .discover .learning-guide-link {
    margin-left: auto;
  }

  .discover .split,
  .discover .split.flipped {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }

  .discover .split.flipped .split-copy {
    order: 0;
  }
}

@media (max-width: 720px) {
  .discover .explore-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .discover .discover-footer {
    flex-direction: column;
    align-items: flex-start;
    gap: 18px;
  }

  .discover .foot-links {
    flex-wrap: wrap;
    gap: 10px 18px;
  }
}
</style>
