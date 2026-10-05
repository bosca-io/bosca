<script setup lang="ts">
withDefaults(defineProps<{
  accent?: string
}>(), {
  accent: '#7c5cff',
})

// Tenant branding from the shared `admin.overrides` config — the same source
// that drives the in-app sidebar brand. When present, it's shown alongside the
// Bosca mark so the sign-in screen reflects the customer's identity.
const { expandedLogo, title, hideTitle, hasOverrides } = useAppOverrides()
</script>

<template>
  <div class="brand-panel">
    <!-- Glass orbs background -->
    <div class="orbs">
      <div class="orb orb-1" :style="{ background: `radial-gradient(circle, ${accent}, transparent 70%)` }" />
      <div class="orb orb-2" />
      <div class="orb orb-3" />
      <svg class="ring-svg" viewBox="0 0 600 800" preserveAspectRatio="xMidYMid slice">
        <defs>
          <linearGradient
            id="glass-ring"
            x1="0"
            y1="0"
            x2="1"
            y2="1">
            <stop offset="0" :stop-color="accent" stop-opacity="0.4" />
            <stop offset="1" stop-color="#5ec5ff" stop-opacity="0" />
          </linearGradient>
        </defs>
        <circle
          cx="320"
          cy="420"
          r="180"
          fill="none"
          stroke="url(#glass-ring)"
          stroke-width="1" />
        <circle
          cx="320"
          cy="420"
          r="240"
          fill="none"
          stroke="url(#glass-ring)"
          stroke-width="1"
          opacity="0.5" />
        <ellipse
          cx="320"
          cy="420"
          rx="280"
          ry="80"
          fill="none"
          stroke="url(#glass-ring)"
          stroke-width="1"
          opacity="0.3"
          transform="rotate(-18 320 420)" />
      </svg>
    </div>

    <!-- Top: brand mark -->
    <div class="brand-top">
      <BoscaMark :size="22" :color="accent" />
      <span class="brand-name">Bosca</span>
      <span class="brand-sep" />
      <span class="brand-label">Studio</span>

      <!-- Tenant branding override, shown next to the Bosca mark -->
      <div v-if="hasOverrides" class="brand-override">
        <img
          v-if="expandedLogo"
          :src="expandedLogo"
          :alt="title ?? 'Logo'"
          class="brand-override-logo"
        >
        <span v-if="title && !hideTitle" class="brand-override-title">{{ title }}</span>
      </div>
    </div>

    <!-- Bottom: copy -->
    <div class="brand-bottom">
      <span class="brand-eyebrow mono">Bosca Studio</span>
      <h2 class="brand-headline">One platform for content, collaboration, and delivery.</h2>
      <p class="brand-body">Manage content and media. Track work with built-in project management. Host Git repositories with pull requests and CI/CD. Run A/B experiments and feature flags. Build AI agents that understand your data. Script the platform in Kotlin. Deploy anywhere.</p>
    </div>
  </div>
</template>

<style scoped>
.brand-panel {
  flex: 1;
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 36px;
  min-width: 0;
  background:
    radial-gradient(80% 60% at 20% 10%, color-mix(in oklch, var(--brand-accent) 28%, transparent), transparent 60%),
    radial-gradient(60% 50% at 90% 90%, color-mix(in oklch, #5ec5ff 22%, transparent), transparent 60%),
    linear-gradient(160deg, #14182a, #0a0c14);
  border-right: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.orbs { position: absolute; inset: 0; pointer-events: none; }
.orb {
  position: absolute;
  border-radius: 50%;
}
.orb-1 {
  top: 12%;
  left: 10%;
  width: 320px;
  height: 320px;
  filter: blur(40px);
  opacity: 0.55;
}
.orb-2 {
  bottom: 14%;
  right: 8%;
  width: 280px;
  height: 280px;
  background: radial-gradient(circle, #5ec5ff, transparent 70%);
  filter: blur(50px);
  opacity: 0.4;
}
.orb-3 {
  top: 50%;
  left: 50%;
  width: 180px;
  height: 180px;
  background: radial-gradient(circle, #ff7ac6, transparent 70%);
  filter: blur(60px);
  opacity: 0.25;
  transform: translate(-50%, -50%);
}
.ring-svg {
  width: 100%;
  height: 100%;
  position: absolute;
  inset: 0;
  opacity: 0.5;
}

.brand-top {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  z-index: 2;
}
.brand-name { font-size: 14px; font-weight: 600; letter-spacing: -0.01em; }
.brand-sep { width: 1px; height: 16px; background: var(--fg-2); }
.brand-label { font-size: 13px; color: var(--fg-2); font-weight: 500; }
.brand-override {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: 28px;
}
.brand-override-logo {
  height: 22px;
  width: auto;
  max-width: 160px;
  object-fit: contain;
}
.brand-override-title {
  font-size: 13px;
  color: var(--fg-0);
  font-weight: 500;
  white-space: nowrap;
}

.brand-bottom {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.brand-eyebrow {
  font-size: 11px;
  color: var(--brand-accent);
  text-transform: uppercase;
  letter-spacing: 0.16em;
  font-weight: 600;
}
.brand-headline {
  margin: 0;
  font-size: 30px;
  line-height: 1.15;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: #f3f5fb;
  max-width: 380px;
}
.brand-body {
  margin: 0;
  font-size: 13.5px;
  color: #969cb1;
  line-height: 1.55;
  max-width: 360px;
}
</style>
