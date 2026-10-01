<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  block: { name: string; uiConfig: Record<string, unknown>; featuredImageUrl: string | null }
  device: string
}>()

const bg = computed(() => {
  const s = props.block.uiConfig.style as string
  if (s === 'outlined') return 'transparent'
  if (s === 'gradient') return 'linear-gradient(135deg, #6b8f71, #3a86ff)'
  return '#6b8f71'
})

const dismissible = computed(() => props.block.uiConfig.dismissible === true)
const outlined = computed(() => props.block.uiConfig.style === 'outlined')
</script>

<template>
  <div
    class="banner"
    :style="{ background: bg }"
    :class="{ outlined, dismissible }"
  >
    <div class="banner-body">
      <span class="banner-text">{{ block.name }}</span>
      <span v-if="block.uiConfig.actionLabel" class="banner-action">{{ block.uiConfig.actionLabel }}</span>
    </div>
    <button
      v-if="dismissible"
      class="banner-close"
      aria-label="Dismiss banner"
      type="button"
      @click.prevent
    >×</button>
  </div>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 16px;
  border-radius: 12px;
  color: white;
}

.banner.outlined {
  border: 1px solid #6b8f71;
  color: #6b8f71;
}

.banner-body { flex: 1; min-width: 0; }

.banner-text {
  font-size: 14px;
  font-weight: 600;
  display: block;
}

.banner-action {
  font-size: 12px;
  opacity: 0.8;
  margin-top: 4px;
  display: block;
}

.banner-close {
  all: unset;
  flex-shrink: 0;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  color: inherit;
  opacity: 0.8;
}
.banner-close:hover { opacity: 1; background: rgba(255, 255, 255, 0.12); }
.banner.outlined .banner-close:hover { background: rgba(107, 143, 113, 0.12); }
</style>
