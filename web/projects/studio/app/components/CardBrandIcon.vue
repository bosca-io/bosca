<script setup lang="ts">
/** A small card-brand logo glyph derived from the detected brand (VISA / MASTERCARD / AMERICAN_EXPRESS / DISCOVER); a generic card icon otherwise. */
defineProps<{ brand: string }>()
</script>

<template>
  <span class="brand-icon" aria-hidden="true">
    <svg v-if="brand === 'VISA'" viewBox="0 0 36 24">
      <rect
        width="36"
        height="24"
        rx="3"
        fill="#1a1f71" />
      <text
        x="18"
        y="16"
        font-size="9"
        font-weight="700"
        fill="#fff"
        text-anchor="middle"
        font-family="Arial, sans-serif"
        letter-spacing="0.5">VISA</text>
    </svg>
    <svg v-else-if="brand === 'MASTERCARD'" viewBox="0 0 36 24">
      <rect
        width="36"
        height="24"
        rx="3"
        fill="#fff"
        stroke="#e2e2e2" />
      <circle
        cx="15"
        cy="12"
        r="6.5"
        fill="#eb001b" />
      <circle
        cx="21"
        cy="12"
        r="6.5"
        fill="#f79e1b"
        fill-opacity="0.85" />
    </svg>
    <svg v-else-if="brand === 'AMERICAN_EXPRESS'" viewBox="0 0 36 24">
      <rect
        width="36"
        height="24"
        rx="3"
        fill="#2e77bb" />
      <text
        x="18"
        y="15"
        font-size="6.5"
        font-weight="700"
        fill="#fff"
        text-anchor="middle"
        font-family="Arial, sans-serif">AMEX</text>
    </svg>
    <svg v-else-if="brand === 'DISCOVER'" viewBox="0 0 36 24">
      <rect
        width="36"
        height="24"
        rx="3"
        fill="#fff"
        stroke="#e2e2e2" />
      <text
        x="13"
        y="15"
        font-size="5"
        font-weight="700"
        fill="#222"
        text-anchor="middle"
        font-family="Arial, sans-serif">DISCOVER</text>
      <circle
        cx="29"
        cy="12"
        r="5"
        fill="#ff6000" />
    </svg>
    <Icon
      v-else
      name="credit-card"
      :size="18"
      color="var(--fg-3)" />
  </span>
</template>

<style scoped>
.brand-icon { display: inline-flex; align-items: center; }
.brand-icon svg { width: 32px; height: 21px; display: block; border-radius: 3px; }
</style>
