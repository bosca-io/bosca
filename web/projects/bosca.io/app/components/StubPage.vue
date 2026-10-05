<script setup lang="ts">
defineProps<{
  title: string
  description: string
  icon: string
  accent: string
}>()
</script>

<template>
  <div class="doc-content article">
    <div class="stub-page">
      <div
        class="stub-icon"
        :style="{
          background: `color-mix(in srgb, ${accent} 12%, transparent)`,
          color: accent
        }"
      >
        <Icon
          :name="icon"
          :size="24"
        />
      </div>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
    </div>
  </div>
</template>
