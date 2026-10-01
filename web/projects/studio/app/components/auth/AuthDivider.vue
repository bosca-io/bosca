<template>
  <div class="auth-divider">
    <span class="auth-divider-line" />
    <span class="auth-divider-text mono"><slot /></span>
    <span class="auth-divider-line" />
  </div>
</template>

<style scoped>
.auth-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--fg-3);
  font-size: 11px;
}
.auth-divider-line {
  flex: 1;
  height: 1px;
  background: color-mix(in oklch, var(--line) 70%, transparent);
}
.auth-divider-text {
  text-transform: uppercase;
  letter-spacing: 0.14em;
}
</style>
