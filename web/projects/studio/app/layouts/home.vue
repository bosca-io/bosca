<template>
  <div class="home-layout">
    <!-- Ambient brand-accent wash. There is no "current subsystem" on the home
         hub, so we use the neutral brand accent rather than a subsystem accent
         (unlike the in-app default layout, which tints per subsystem). -->
    <div class="home-bg" />
    <div class="home-scroll">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.home-layout {
  width: 100%;
  height: 100%;
  background: var(--bg-0);
  color: var(--fg-0);
  position: relative;
  overflow: hidden;
}

.home-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(72% 52% at 12% -8%, color-mix(in oklch, var(--brand-accent) 22%, transparent), transparent 60%),
    radial-gradient(58% 46% at 100% 0%, color-mix(in oklch, #5ec5ff 13%, transparent), transparent 65%),
    linear-gradient(180deg, color-mix(in oklch, var(--brand-accent) 5%, var(--bg-0)) 0%, var(--bg-0) 42%);
}

.home-scroll {
  position: relative;
  z-index: 1;
  height: 100%;
  overflow-y: auto;
}
</style>
