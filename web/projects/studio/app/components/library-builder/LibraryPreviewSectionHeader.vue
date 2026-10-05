<script setup lang="ts">
import { computed } from 'vue'


const props = defineProps<{ block: { name: string; uiConfig: Record<string, unknown>; featuredImageUrl: string | null }; device: string }>()

const fontSize = computed(() =>
  props.block.uiConfig.style === 'small' ? '14px' : '18px',
)
</script>

<template>
  <div class="section-header">
    <div v-if="block.uiConfig.showDivider !== false" class="divider" />
    <span class="header-text" :style="{ fontSize }">{{ block.name }}</span>
  </div>
</template>

<style scoped>
.section-header { padding: 4px 0; }

.divider {
  height: 1px;
  background: #e8e4df;
  margin-bottom: 8px;
}

.header-text {
  font-weight: 600;
  color: #2c2c2c;
}
</style>
