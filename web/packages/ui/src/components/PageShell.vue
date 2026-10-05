<script setup lang="ts">
</script>

<template>
  <div class="page-shell">
    <slot name="header" />
    <div class="page-content">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.page-shell {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-0);
  overflow: hidden;
}

.page-content {
  flex: 1;
  overflow: auto;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
</style>
