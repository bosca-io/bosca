<script setup lang="ts">
withDefaults(defineProps<{
  eyebrow?: string
  title: string
  sub?: string
  accent?: string
}>(), {
  eyebrow: undefined,
  sub: undefined,
  accent: '#7c5cff'
})
</script>

<template>
  <div class="auth-heading">
    <span
      v-if="eyebrow"
      class="auth-heading-eyebrow mono"
      :style="{ color: accent }"
    >{{ eyebrow }}</span>
    <h1 class="auth-heading-title">
      {{ title }}
    </h1>
    <p
      v-if="sub"
      class="auth-heading-sub"
    >
      {{ sub }}
    </p>
    <slot />
  </div>
</template>

<style scoped>
.auth-heading {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.auth-heading-eyebrow {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  font-weight: 600;
}
.auth-heading-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--fg-0);
}
.auth-heading-sub {
  margin: 0;
  font-size: 13.5px;
  color: var(--fg-3);
  line-height: 1.5;
}
</style>
