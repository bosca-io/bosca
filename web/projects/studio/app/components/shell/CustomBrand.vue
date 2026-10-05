<script setup lang="ts">
const { expandedLogo, title, hideTitle, hasOverrides } = useAppOverrides()
</script>

<template>
  <div v-if="hasOverrides" class="custom-brand">
    <img
      v-if="expandedLogo"
      :src="expandedLogo"
      :alt="title ?? 'Logo'"
      class="custom-brand-logo"
      :class="{ 'custom-brand-logo--solo': hideTitle }"
    >
    <span v-if="title && !hideTitle" class="custom-brand-title">{{ title }}</span>
  </div>
</template>

<style scoped>
.custom-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px 18px;
  min-width: 0;
}

.custom-brand-logo {
  height: 22px;
  width: auto;
  max-width: 100%;
  object-fit: contain;
  flex: 0 0 auto;
}

.custom-brand-logo--solo {
  height: 24px;
  max-height: 28px;
}

.custom-brand-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
  letter-spacing: -0.005em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
</style>
