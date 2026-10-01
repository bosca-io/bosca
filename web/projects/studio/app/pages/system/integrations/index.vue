<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()

interface IntegrationConfig {
  id: string
  key: string
  value: unknown
}

const configsGql = gql`
  query GetIntegrationConfigs {
    sendgrid: configurations { configuration(key: "sendgrid") { id key value } }
    mailgun: configurations { configuration(key: "mailgun") { id key value } }
    push: configurations { configuration(key: "push") { id key value } }
    hubspot: configurations { configuration(key: "hubspot") { id key value } }
    mux: configurations { configuration(key: "mux") { id key value } }
    federation: collaboration { federation { peers { id } } }
  }
`

const { data } = useAsyncQuery<{
  sendgrid: { configuration: IntegrationConfig | null }
  mailgun: { configuration: IntegrationConfig | null }
  push: { configuration: IntegrationConfig | null }
  hubspot: { configuration: IntegrationConfig | null }
  mux: { configuration: IntegrationConfig | null }
  federation: { federation: { peers: Array<{ id: string }> } }
}>('integration-configs', configsGql, {}, { server: false })

interface IntegrationCard {
  id: string
  name: string
  description: string
  icon: string
  route: string
  config: () => IntegrationConfig | null
  connected: () => boolean
}

const integrations: IntegrationCard[] = [
  {
    id: 'sendgrid',
    name: 'SendGrid',
    description: 'Email delivery and signed delivery-event webhooks',
    icon: 'mail',
    route: '/system/integrations/sendgrid',
    config: () => data.value?.sendgrid?.configuration ?? null,
    connected: () => {
      const value = data.value?.sendgrid?.configuration?.value as { apiKey?: string } | undefined
      return !!value?.apiKey
    },
  },
  {
    id: 'mailgun',
    name: 'Mailgun',
    description: 'Email delivery through a verified Mailgun sending domain',
    icon: 'mail',
    route: '/system/integrations/mailgun',
    config: () => data.value?.mailgun?.configuration ?? null,
    connected: () => {
      const value = data.value?.mailgun?.configuration?.value as {
        apiKey?: string
        domain?: string
      } | undefined
      return !!value?.apiKey && !!value.domain
    },
  },
  {
    id: 'push',
    name: 'Push Notifications',
    description: 'Mobile and web delivery through Firebase and APNs',
    icon: 'bell',
    route: '/system/integrations/push',
    config: () => data.value?.push?.configuration ?? null,
    connected: () => {
      const value = data.value?.push?.configuration?.value as {
        enabled?: boolean
        fcm?: object
        apns?: { teamId?: string; keyId?: string; bundleId?: string; privateKey?: string }
      } | undefined
      const apnsConfigured = !!value?.apns?.teamId
        && !!value.apns.keyId
        && !!value.apns.bundleId
        && !!value.apns.privateKey
      return value?.enabled === true && (value.fcm != null || apnsConfigured)
    },
  },
  {
    id: 'hubspot',
    name: 'HubSpot',
    description: 'CRM integration for contact and organization sync',
    icon: 'globe',
    route: '/system/integrations/hubspot',
    config: () => data.value?.hubspot?.configuration ?? null,
    connected: () => {
      const value = data.value?.hubspot?.configuration?.value as { token?: string } | undefined
      return !!value?.token
    },
  },
  {
    id: 'mux',
    name: 'Mux',
    description: 'Video hosting, encoding, and streaming',
    icon: 'video',
    route: '/system/integrations/mux',
    config: () => data.value?.mux?.configuration ?? null,
    connected: () => {
      const value = data.value?.mux?.configuration?.value as { tokenId?: string } | undefined
      return !!value?.tokenId
    },
  },
  {
    id: 'federation',
    name: 'Federation',
    description: 'Cross-instance chat through registered Bosca peers',
    icon: 'link',
    route: '/system/integrations/federation',
    config: () => null,
    connected: () => (data.value?.federation?.federation?.peers.length ?? 0) > 0,
  },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Integrations')"
        title="Integrations"
        subtitle="Connect and configure external services"
      />
    </template>

    <div class="int-grid">
      <NuxtLink
        v-for="int in integrations"
        :key="int.id"
        :to="int.route"
        class="int-card"
      >
        <div class="int-header">
          <span
            class="int-icon"
            :style="{
              background: `color-mix(in oklch, ${accent} 14%, var(--bg-2))`,
              border: `1px solid color-mix(in oklch, ${accent} 26%, transparent)`,
            }"
          >
            <Icon :name="int.icon" :size="16" :color="accent" />
          </span>
          <div class="int-info">
            <span class="int-name">{{ int.name }}</span>
            <span class="int-desc">{{ int.description }}</span>
          </div>
        </div>
        <div class="int-footer">
          <Badge :color="int.connected() ? '#34d99a' : '#6c7388'">
            {{ int.connected() ? 'Connected' : 'Not configured' }}
          </Badge>
          <span class="int-cta">
            Configure
            <Icon name="chevronDown" :size="12" :color="accent" />
          </span>
        </div>
      </NuxtLink>
    </div>
  </PageShell>
</template>

<style scoped>
.int-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.int-card {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  text-decoration: none;
  color: inherit;
  transition: border-color 0.15s, background 0.15s;
}

.int-card:hover {
  border-color: var(--fg-4);
  background: var(--bg-2);
}

.int-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.int-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-sm);
  flex: 0 0 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.int-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.int-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.int-desc {
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.4;
}

.int-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.int-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 540;
  color: var(--fg-2);
}

.int-cta :deep(svg) {
  transform: rotate(-90deg);
}
</style>
