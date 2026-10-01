<script setup lang="ts">
import type { NuxtError } from '#app'

const props = defineProps<{ error: NuxtError }>()

const isNotFound = computed(() => props.error.statusCode === 404)

useSeoMeta({
  title: isNotFound.value ? 'Page not found' : 'Something went wrong',
  robots: 'noindex'
})

function goHome() {
  clearError({ redirect: '/' })
}
</script>

<template>
  <div
    class="error-page"
    data-theme="dark"
  >
    <BoscaMark :size="56" />
    <p class="error-code">
      {{ error.statusCode }}
    </p>
    <h1>{{ isNotFound ? 'Page not found' : 'Something went wrong' }}</h1>
    <p class="error-sub">
      {{ isNotFound
        ? "The page you're looking for doesn't exist or has moved."
        : 'An unexpected error occurred. Try again, or head back to the start.' }}
    </p>
    <div class="error-actions">
      <button
        class="btn-primary"
        @click="goHome"
      >
        Back to bosca.io
      </button>
      <NuxtLink
        to="/discover/bml"
        class="btn-secondary"
      >
        Discover BML
      </NuxtLink>
    </div>
  </div>
</template>

<style scoped>
.error-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px;
  text-align: center;
  background: var(--bg-0);
  color: var(--fg-0);
  font-family: var(--font-sans);
}

.error-code {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-3);
  margin-top: 18px;
}

.error-page h1 {
  font-size: clamp(28px, 4vw, 40px);
  font-weight: 700;
  letter-spacing: -0.03em;
  margin: 0;
}

.error-sub {
  font-size: 15px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 420px;
  margin: 8px 0 22px;
}

.error-actions {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  justify-content: center;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  padding: 10px 22px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-primary {
  background: #00dc82;
  color: #04150c;
  border: none;
}

.btn-primary:hover {
  background: #33e59b;
}

.btn-secondary {
  color: var(--fg-0);
  border: 1px solid var(--line-2);
  background: transparent;
}

.btn-secondary:hover {
  border-color: #00dc82;
  color: #00dc82;
}
</style>
