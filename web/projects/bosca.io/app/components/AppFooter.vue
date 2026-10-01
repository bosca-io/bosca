<script setup lang="ts">
interface FooterLink {
  label: string
  to: string
}

const columns: { label: string, children: FooterLink[] }[] = [{
  label: 'Developers',
  children: [{
    label: 'Getting Started',
    to: '/developers/getting-started'
  }, {
    label: 'Architecture',
    to: '/developers/architecture'
  }, {
    label: 'GraphQL',
    to: '/developers/graphql'
  }, {
    label: 'Permissions',
    to: '/developers/permissions'
  }]
}, {
  label: 'Platform',
  children: [{
    label: 'Content',
    to: '/discover/cms'
  }, {
    label: 'Work Ops',
    to: '/discover/workops'
  }, {
    label: 'Git',
    to: '/discover/git'
  }, {
    label: 'AI',
    to: '/discover/ai'
  }]
}, {
  label: 'Bosca',
  children: [{
    label: 'bosca.io',
    to: 'https://bosca.io'
  }]
}]

const legalLinks = useLegalLinks()
</script>

<template>
  <footer class="app-footer">
    <div class="footer-columns">
      <div
        v-for="col in columns"
        :key="col.label"
      >
        <div class="footer-col-label">
          {{ col.label }}
        </div>
        <div
          v-for="link in col.children"
          :key="link.label"
          class="footer-link-row"
        >
          <NuxtLink
            :to="link.to"
            :target="link.to.startsWith('http') ? '_blank' : undefined"
            class="footer-link"
          >
            {{ link.label }}
          </NuxtLink>
        </div>
      </div>
    </div>
    <div
      v-if="legalLinks.length > 0"
      class="footer-bottom"
    >
      <nav class="footer-legal">
        <NuxtLink
          v-for="link in legalLinks"
          :key="link.label"
          :to="link.url"
          external
        >
          {{ link.label }}
        </NuxtLink>
      </nav>
    </div>
  </footer>
</template>

<style scoped>
.app-footer {
  border-top: 1px solid var(--line);
  margin-top: 60px;
  padding: 32px 40px;
}

.footer-columns {
  display: flex;
  flex-wrap: wrap;
  gap: 28px 60px;
  max-width: var(--doc-max-width);
  margin: 0 auto;
}

.footer-col-label {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-3);
  margin-bottom: 12px;
}

.footer-link-row {
  margin-bottom: 8px;
}

.footer-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--fg-2);
  text-decoration: none;
}

.footer-bottom {
  max-width: var(--doc-max-width);
  margin: 24px auto 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--fg-4);
}

.footer-legal {
  display: flex;
  gap: 18px;
}

.footer-legal a {
  color: var(--fg-3);
  text-decoration: none;
}

.footer-legal a:hover {
  color: var(--fg-1);
}

@media (max-width: 640px) {
  .app-footer {
    padding: 28px 20px;
  }

  .footer-columns {
    gap: 24px 40px;
  }
}
</style>
