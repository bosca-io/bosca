<template>
  <div class="auth-layout" data-theme="dark">
    <AuthBrandPanel class="auth-layout-brand" />
    <div class="auth-layout-form">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.auth-layout {
  width: 100%;
  height: 100%;
  background: #0a0c14;
  color: var(--fg-0);
  display: flex;
  overflow: hidden;
  position: relative;
}
.auth-layout-brand {
  display: flex;
}
.auth-layout-form {
  flex: 0 0 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 36px;
  background:
    radial-gradient(80% 60% at 100% 0%, color-mix(in oklch, var(--brand-accent) 14%, transparent), transparent 60%),
    #0a0c14;
  position: relative;
}

@media (max-width: 768px) {
  .auth-layout-brand { display: none; }
  .auth-layout-form {
    flex: 1;
    padding: 24px 16px;
  }
}
</style>
