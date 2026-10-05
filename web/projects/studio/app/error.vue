<script setup lang="ts">
import type { NuxtError } from '#app'

const props = defineProps<{ error: NuxtError }>()

const code = computed(() => props.error.statusCode || 500)

const config = computed(() => {
  switch (code.value) {
    case 401:
      return {
        label: 'Unauthorized',
        headline: 'Sign in required',
        description: 'You need to be authenticated to access this page. Please sign in and try again.',
        action: { label: 'Go to sign in', href: '/auth/login' },
        accentColor: '#ffb547',
        icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2c-2.5 0-4.71-1.28-6-3.22.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08-1.29 1.94-3.5 3.22-6 3.22z',
      }
    case 404:
      return {
        label: 'Not found',
        headline: 'Page not found',
        description: 'The page you\'re looking for doesn\'t exist or may have been moved.',
        action: { label: 'Back to dashboard', href: '/' },
        accentColor: '#5ec5ff',
        icon: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
      }
    default:
      return {
        label: 'Server error',
        headline: 'Something went wrong',
        description: 'An unexpected error occurred on our end. Please try again in a moment.',
        action: { label: 'Back to dashboard', href: '/' },
        accentColor: '#ff5470',
        icon: 'M12 2L1 21h22L12 2zm0 3.99L19.53 19H4.47L12 5.99zM11 16h2v2h-2v-2zm0-6h2v4h-2v-4z',
      }
  }
})

async function handleAction() {
  await clearError()
  await navigateTo(config.value.action.href, { external: true })
}
</script>

<template>
  <div class="error-page">
    <div
      class="error-ambient"
      :style="{
        background: `
          radial-gradient(60% 50% at 50% 30%, color-mix(in oklch, ${config.accentColor} 16%, transparent), transparent 70%),
          radial-gradient(80% 60% at 20% 80%, color-mix(in oklch, var(--brand-accent) 10%, transparent), transparent 60%),
          radial-gradient(70% 50% at 90% 10%, color-mix(in oklch, var(--brand-1) 8%, transparent), transparent 65%)
        `,
      }"
    />

    <div class="error-brand">
      <svg
        width="22"
        height="22"
        viewBox="0 0 512 512"
        fill="none"
        aria-label="Bosca">
        <path d="M256 262L491 138L256 21L21 138L256 262Z" fill="#00c16a" />
        <path d="M491 138L256 262V498L491 372V138Z" fill="#00dc82" />
        <path d="M21 138L256 262V498L21 372V138Z" fill="#00a155" />
      </svg>
      <span class="error-brand-text">
        <span class="error-brand-name">Bosca</span>
        <span class="error-brand-separator" />
        <span class="error-brand-studio">Studio</span>
      </span>
    </div>

    <div class="error-content">
      <div class="error-icon-ring" :style="{ '--accent': config.accentColor }">
        <svg viewBox="0 0 24 24" class="error-icon" :style="{ fill: config.accentColor }">
          <path :d="config.icon" />
        </svg>
      </div>

      <div class="error-code mono" :style="{ color: config.accentColor }">{{ code }}</div>

      <span class="error-label">{{ config.label }}</span>

      <h1 class="error-headline">{{ config.headline }}</h1>

      <p class="error-description">{{ config.description }}</p>

      <button
        class="error-btn"
        :style="{
          border: `1px solid color-mix(in srgb, ${config.accentColor} 60%, transparent)`,
        }"
        @click="handleAction"
      >
        <svg
          viewBox="0 0 16 16"
          width="14"
          height="14"
          :style="{ fill: config.accentColor }">
          <path d="M6.22 3.22a.75.75 0 011.06 0l4.25 4.25a.75.75 0 010 1.06l-4.25 4.25a.75.75 0 01-1.06-1.06L9.94 8 6.22 4.28a.75.75 0 010-1.06z" />
        </svg>
        {{ config.action.label }}
      </button>

      <p v-if="error.message && code >= 500" class="error-detail mono">{{ error.message }}</p>
    </div>

  </div>
</template>

<style scoped>
.error-page {
  width: 100%;
  height: 100%;
  background: var(--bg-0);
  color: var(--fg-0);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  font-family: var(--font-sans);
  -webkit-font-smoothing: antialiased;
}

.error-ambient {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.error-brand {
  position: absolute;
  top: 0;
  left: 0;
  height: 76px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
}

.error-brand-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.error-brand-name {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.error-brand-separator {
  width: 1px;
  height: 18px;
  background: var(--fg-4);
  align-self: center;
}

.error-brand-studio {
  font-size: 13px;
  color: var(--fg-2);
  font-weight: 500;
}

.error-content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  max-width: 420px;
  padding: 0 24px;
  gap: 0;
}

.error-icon-ring {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in oklch, var(--accent) 10%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--accent) 20%, transparent);
  box-shadow: 0 0 40px -10px color-mix(in oklch, var(--accent) 30%, transparent);
  margin-bottom: 24px;
}

.error-icon {
  width: 28px;
  height: 28px;
}

.error-code {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.06em;
  margin-bottom: 8px;
}

.error-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 12px;
}

.error-headline {
  font-size: 28px;
  font-weight: 650;
  letter-spacing: -0.025em;
  color: var(--fg-0);
  margin: 0 0 10px;
  line-height: 1.2;
}

.error-description {
  font-size: 14.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0 0 28px;
}

.error-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 550;
  color: var(--fg-1);
  background: var(--bg-2);
  cursor: pointer;
  line-height: 1;
}

.error-detail {
  margin-top: 24px;
  font-size: 12px;
  color: var(--fg-4);
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 10px 14px;
  max-width: 100%;
  word-break: break-word;
}

@media (max-width: 480px) {
  .error-headline { font-size: 22px; }
  .error-description { font-size: 13.5px; }
  .error-icon-ring { width: 60px; height: 60px; }
  .error-icon { width: 24px; height: 24px; }
}
</style>
