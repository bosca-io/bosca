<template>
  <div class="welcome-layout">
    <div class="welcome-bg">
      <div class="orb orb-1" />
      <div class="orb orb-2" />
      <div class="orb orb-3" />
    </div>
    <div class="welcome-content">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.welcome-layout {
  width: 100%;
  height: 100%;
  background: #0a0c14;
  color: var(--fg-0);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  position: relative;
}

.welcome-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
}

.orb-1 {
  top: 8%;
  left: 15%;
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, var(--brand-accent), transparent 70%);
  filter: blur(60px);
  opacity: 0.3;
}

.orb-2 {
  bottom: 10%;
  right: 12%;
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, #5ec5ff, transparent 70%);
  filter: blur(60px);
  opacity: 0.25;
}

.orb-3 {
  top: 45%;
  left: 55%;
  width: 200px;
  height: 200px;
  background: radial-gradient(circle, #ff7ac6, transparent 70%);
  filter: blur(70px);
  opacity: 0.18;
  transform: translate(-50%, -50%);
}

.welcome-content {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 900px;
  padding: 24px;
}
</style>
