<script setup lang="ts">
withDefaults(defineProps<{
  width?: number
}>(), {
  width: 380
})
</script>

<template>
  <div
    class="auth-form-card"
    :style="{ width: width + 'px' }"
  >
    <slot />
  </div>
</template>

<style scoped>
.auth-form-card {
  /* The width prop is a target, not a floor — never exceed the viewport. */
  max-width: 100%;
  padding: 36px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  background: rgba(20, 24, 34, 0.6);
  backdrop-filter: blur(28px) saturate(1.2);
  -webkit-backdrop-filter: blur(28px) saturate(1.2);
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 30px 80px -30px rgba(0, 0, 0, 0.6);
}

@media (max-width: 480px) {
  .auth-form-card {
    padding: 28px 22px;
  }
}
</style>
