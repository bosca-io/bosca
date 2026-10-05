<script setup lang="ts">
withDefaults(defineProps<{
  name: string
  size?: number
  color?: string
}>(), {
  size: 16,
  color: 'currentColor',
})

const socialIcons = ['google', 'github', 'microsoft'] as const
</script>

<template>
  <svg
    v-if="socialIcons.includes(name as any)"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    :stroke="color"
    fill="none"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round">
    <template v-if="name === 'google'">
      <path d="M21.6 12.2c0-.7-.06-1.3-.18-2H12v3.8h5.4c-.23 1.2-.94 2.2-2 2.9v2.4h3.2c1.9-1.7 3-4.3 3-7.1z" fill="#4285f4" stroke="none" />
      <path d="M12 22c2.7 0 5-1 6.6-2.7l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.7-5.6-4.1H3.1v2.6C4.7 19.6 8.1 22 12 22z" fill="#34a853" stroke="none" />
      <path d="M6.4 13.7c-.2-.6-.3-1.2-.3-1.7s.1-1.1.3-1.7V7.7H3.1C2.4 9 2 10.4 2 12s.4 3 1.1 4.3l3.3-2.6z" fill="#fbbc04" stroke="none" />
      <path d="M12 5.8c1.5 0 2.8.5 3.8 1.5l2.8-2.8C16.9 2.9 14.7 2 12 2 8.1 2 4.7 4.4 3.1 7.7l3.3 2.6c.8-2.4 3-4.1 5.6-4.1z" fill="#ea4335" stroke="none" />
    </template>
    <template v-else-if="name === 'github'">
      <path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.9 1.52 2.34 1.08 2.91.83.09-.65.35-1.08.63-1.33-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.99 1.03-2.69-.1-.25-.45-1.27.1-2.65 0 0 .84-.27 2.75 1.02a9.5 9.5 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.38.2 2.4.1 2.65.64.7 1.03 1.6 1.03 2.69 0 3.84-2.34 4.69-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2z" :fill="color" stroke="none" />
    </template>
    <template v-else-if="name === 'microsoft'">
      <rect
        x="3"
        y="3"
        width="8"
        height="8"
        fill="#f25022"
        stroke="none" />
      <rect
        x="13"
        y="3"
        width="8"
        height="8"
        fill="#7fba00"
        stroke="none" />
      <rect
        x="3"
        y="13"
        width="8"
        height="8"
        fill="#00a4ef"
        stroke="none" />
      <rect
        x="13"
        y="13"
        width="8"
        height="8"
        fill="#ffb900"
        stroke="none" />
    </template>
  </svg>
  <Icon
    v-else
    :name="name"
    :size="size"
    :color="color" />
</template>
