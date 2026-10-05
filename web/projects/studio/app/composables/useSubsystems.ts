import type { Subsystem } from '~~/shared/types'

function hexToHue(hex: string): number {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  if (d === 0) return 0
  let h = 0
  if (max === r) h = ((g - b) / d + 6) % 6
  else if (max === g) h = (b - r) / d + 2
  else h = (r - g) / d + 4
  return Math.round(h * 60)
}

const BOSCA_BASE_HUE = 140

export function accentHueShift(subsystemId: string): number {
  const sys = SUBSYSTEMS.find(s => s.id === subsystemId)
  if (!sys) return 0
  return hexToHue(sys.accent) - BOSCA_BASE_HUE
}

export const SUBSYSTEMS: Subsystem[] = [
  {
    id: 'analytics',
    label: 'Analytics',
    sub: 'Track events, build dashboards, and surface insights',
    icon: 'dashboard',
    accent: '#268e71',
    nav: [
      { group: 'Dashboards', items: [
        { id: 'dashboard', label: 'Dashboard', icon: 'dashboard' },
      ] },
      { group: 'Administration', admin: true, items: [
        { id: 'events', label: 'Raw Events', icon: 'list' },
        { id: 'errors', label: 'Errors', icon: 'alert' },
        { id: 'dashboards', label: 'Dashboards', icon: 'dashboard' },
        { id: 'queries', label: 'Queries', icon: 'database' },
        { id: 'visualizations', label: 'Visualizations', icon: 'pulse' },
      ] },
    ],
  },
  {
    id: 'cms',
    label: 'CMS',
    sub: 'Manage collections, documents, and media assets',
    icon: 'library',
    accent: '#f7828c',
    nav: [
      { group: 'Library', items: [
        { id: 'collections', label: 'Collections', icon: 'boxes' },
        { id: 'metadata', label: 'Everything', icon: 'inspect' },
        { id: 'documents', label: 'Documents', icon: 'file' },
        { id: 'guides', label: 'Guides', icon: 'route' },
        { id: 'library-builder', label: 'Library Builder', icon: 'wand' },
        { id: 'data', label: 'Data', icon: 'database' },
        { id: 'bibles', label: 'Bibles', icon: 'book' },
      ] },
      { group: 'Moderation', items: [
          { id: 'comments', label: 'Comments', icon: 'message-square' },
      ] },
      { group: 'Distribution', items: [
        { id: 'health', label: 'Content Health', icon: 'pulse' },
      ] },
      { group: 'Settings', items: [
        { id: 'settings/sources', label: 'Sources', icon: 'globe' },
        { id: 'settings/templates', label: 'Templates', icon: 'wand' },
        { id: 'settings/categories', label: 'Categories', icon: 'tag' },
        { id: 'settings/collection-assignments', label: 'Collection Assignments', icon: 'folder' },
        { id: 'settings/template-tools', label: 'Template Tools', icon: 'wrench' },
        { id: 'settings/time-event-types', label: 'Time Event Types', icon: 'clock' },
        { id: 'settings/states', label: 'States', icon: 'workflow' },
        { id: 'settings/transitions', label: 'Transitions', icon: 'route' },
        { id: 'settings/search', label: 'Search Indexing', icon: 'search' },
      ] },
    ],
  },
  {
    id: 'feeds',
    label: 'Feeds',
    sub: 'Ingest external content feeds and preview what they bring in',
    icon: 'rss',
    accent: '#42daf4',
    nav: [
      { group: 'Overview', items: [
        { id: '', label: 'Overview', icon: 'dashboard' },
      ] },
      { group: 'Manage', items: [
        { id: 'sources', label: 'Sources', icon: 'globe' },
      ] },
      { group: 'Read', items: [
        { id: 'preview', label: 'Preview', icon: 'book-open' },
      ] },
    ],
  },
  {
    id: 'calendar',
    label: 'Calendar',
    sub: 'Schedule events and coordinate timelines',
    icon: 'calendar',
    accent: '#7659f2',
    nav: [
      { group: 'Views', items: [
        { id: '', label: 'Calendar', icon: 'calendar' },
      ] },
    ],
  },
  {
    id: 'forms',
    label: 'Forms',
    sub: 'Build forms, capture responses, and manage submissions',
    icon: 'form',
    accent: '#f6399c',
    nav: [
      { group: 'Build', items: [
        { id: 'builder', label: 'Builder', icon: 'wand' },
      ] },
      { group: 'Capture', items: [
        { id: 'submissions', label: 'Submissions', icon: 'inbox' },
        { id: 'antispam', label: 'Anti-spam', icon: 'shield' },
      ] },
    ],
  },
  {
    id: 'localization',
    label: 'Localization',
    sub: 'Translate content and manage locale workflows',
    icon: 'languages',
    accent: '#49e93c',
    nav: [
      { group: 'Workspace', items: [
        { id: 'locales', label: 'Projects', icon: 'languages' },
        { id: 'languages', label: 'Locales', icon: 'globe' },
        { id: 'language-mappings', label: 'Language Mappings', icon: 'arrow-right-left' },
      ] },
    ],
  },
  {
    id: 'workops',
    label: 'Work Ops',
    sub: 'Plan sprints, track tasks, and manage project delivery',
    icon: 'kanban',
    accent: '#91a6f6',
    nav: [
      { group: 'Activity', items: [
        { id: 'inbox', label: 'Inbox', icon: 'inbox' },
        { id: 'tasks', label: 'Tasks', icon: 'check' },
        { id: 'specs', label: 'Specs', icon: 'file-text' },
        { id: 'filters', label: 'Filters', icon: 'filter' },
      ] },
      { group: 'Plan', items: [
        { id: 'boards', label: 'Boards', icon: 'columns' },
        { id: 'sprints', label: 'Sprints', icon: 'refresh' },
        { id: 'projects', label: 'Projects', icon: 'folder' },
        { id: 'programs', label: 'Programs', icon: 'layers' },
        { id: 'portfolios', label: 'Portfolios', icon: 'building' },
        { id: 'milestones', label: 'Milestones', icon: 'flag' },
      ] },
      { group: 'Deliver', items: [
        { id: 'releases', label: 'Releases', icon: 'package' },
        { id: 'releases/environments', label: 'Environments', icon: 'globe' },
        { id: 'releases/versions', label: 'Versions', icon: 'tag' },
      ] },
      { group: 'Settings', items: [
        { id: 'settings/task-types', label: 'Task Types', icon: 'list' },
        { id: 'settings/environment-types', label: 'Environment Types', icon: 'globe' },
        { id: 'settings/statuses', label: 'Statuses', icon: 'check' },
        { id: 'settings/priorities', label: 'Priorities', icon: 'flag' },
        { id: 'settings/labels', label: 'Labels', icon: 'tag' },
        { id: 'settings/workflows', label: 'Workflows', icon: 'workflow' },
        { id: 'settings/automation', label: 'Automation', icon: 'wand' },
        { id: 'settings/link-types', label: 'Link Types', icon: 'link' },
        { id: 'settings/resolutions', label: 'Resolutions', icon: 'check' },
        { id: 'settings/sla', label: 'SLA', icon: 'pulse' },
                { id: 'settings/components', label: 'Components', icon: 'puzzle' },
        { id: 'settings/permissions', label: 'Permissions', icon: 'shield' },
        { id: 'settings/notifications', label: 'Notifications', icon: 'bell' },
        { id: 'settings/milestones', label: 'Milestones', icon: 'flag' },
      ] },
    ],
  },
  {
    id: 'git',
    label: 'Git',
    sub: 'Browse repositories, review pull requests, and search code',
    icon: 'git-branch',
    accent: '#64748b',
    nav: [
      { group: 'Code', items: [
          { id: 'search', label: 'Search', icon: 'search' },
          { id: 'repositories', label: 'Repositories', icon: 'folder' },
          { id: 'pulls', label: 'Pull Requests', icon: 'git-merge' },
          { id: 'pipelines', label: 'Pipelines', icon: 'workflow' },
        ] },
      { group: 'Settings', items: [
          { id: 'ci-agents', label: 'CI Agents', icon: 'wand' },
          { id: 'settings/webhooks', label: 'Webhooks', icon: 'globe' },
          { id: 'settings/protection', label: 'Branch Protection', icon: 'shield' },
          { id: 'settings/permissions', label: 'Permissions', icon: 'lock' },
          { id: 'settings/utilities', label: 'Utilities', icon: 'wrench' },
        ] },
    ],
  },
  {
    id: 'gateway',
    label: 'Gateway',
    sub: 'Proxy authenticated traffic to upstream HTTP services',
    icon: 'globe',
    accent: '#c3cf3a',
    nav: [
      { group: 'Topology', items: [
        { id: 'gateways', label: 'Gateways', icon: 'globe' },
        { id: 'routes', label: 'Routes', icon: 'route' },
      ] },
    ],
  },
  {
    id: 'kubernetes',
    label: 'Kubernetes',
    sub: 'Monitor clusters, manage workloads, and configure infrastructure',
    icon: 'kubernetes',
    accent: '#5e8df4',
    nav: [
      { group: 'Cluster', items: [
        { id: 'overview', label: 'Overview', icon: 'dashboard' },
        { id: 'nodes', label: 'Nodes', icon: 'database' },
        { id: 'events', label: 'Events', icon: 'pulse' },
      ] },
      { group: 'Workloads', items: [
        { id: 'workloads', label: 'Workloads', icon: 'boxes' },
        { id: 'pods', label: 'Pods', icon: 'container' },
        { id: 'jobs', label: 'Jobs & Cron', icon: 'refresh' },
        { id: 'scaling', label: 'Autoscaling & PDBs', icon: 'maximize' },
      ] },
      { group: 'Traffic', items: [
        { id: 'network', label: 'Networking', icon: 'globe' },
        { id: 'gateways', label: 'Gateway API', icon: 'route' },
      ] },
      { group: 'Config', items: [
        { id: 'config', label: 'Config & Secrets', icon: 'lock' },
        { id: 'storage', label: 'Storage', icon: 'database' },
        { id: 'namespaces', label: 'Namespaces', icon: 'tag' },
      ] },
      { group: 'Platform', items: [
        { id: 'crds', label: 'Custom Resources', icon: 'folder' },
        { id: 'access', label: 'Access Control', icon: 'shield' },
      ] },
      { group: 'Helm', items: [
        { id: 'helm', label: 'Releases', icon: 'archive' },
        { id: 'helm/catalog', label: 'Catalog', icon: 'boxes' },
        { id: 'helm/repos', label: 'Repos', icon: 'database' },
      ] },
      { group: 'Add-ons', items: [
        { id: 'certs', label: 'cert-manager', icon: 'shield' },
        { id: 'cnpg', label: 'CloudNativePG', icon: 'database' },
      ] },
      { group: 'Settings', items: [
        { id: 'settings/clusters', label: 'Registered Clusters', icon: 'container' },
      ] },
    ],
  },
  {
    id: 'communications',
    label: 'Communications',
    sub: 'Run campaigns, send messages, manage channels, and collaborate in real time',
    icon: 'message',
    accent: '#2272f2',
    nav: [
      { group: 'Campaigns', items: [
        { id: 'campaigns', label: 'Campaigns', icon: 'megaphone' },
      ] },
      { group: 'Chat', items: [
        { id: 'channels', label: 'Channels', icon: 'message' },
      ] },
      { group: 'Messages', items: [
        { id: 'email/preview', label: 'Email Preview', icon: 'eye' },
        { id: 'messages/branding', label: 'Message Branding', icon: 'brush' },
        { id: 'delivery', label: 'Delivery Status', icon: 'pulse' },
        { id: 'preferences', label: 'Preferences', icon: 'settings' },
        { id: 'suppression', label: 'Suppression List', icon: 'shield' },
      ] },
    ],
  },
  {
    id: 'ai',
    label: 'AI',
    sub: 'Build agents, manage prompts, and connect AI models',
    icon: 'ai',
    accent: '#f9b18f',
    nav: [
      { group: 'Kit', items: [
        { id: 'chat', label: 'Chat', icon: 'message' },
        { id: 'history', label: 'History', icon: 'list' },
      ] },
      { group: 'Build', items: [
        { id: 'agents', label: 'Agents', icon: 'wand' },
        { id: 'agent-tools', label: 'Agent Tools', icon: 'gear' },
        { id: 'agent-resources', label: 'Agent Resources', icon: 'file' },
        { id: 'prompts', label: 'Prompts', icon: 'file' },
      ] },
      { group: 'Infrastructure', items: [
        { id: 'models', label: 'Models', icon: 'database' },
        { id: 'mcp-servers', label: 'MCP Servers', icon: 'globe' },
      ] },
    ],
  },
  {
    id: 'audience',
    label: 'Audience',
    sub: 'Organize profiles and build audience segments',
    icon: 'target',
    accent: '#da9e30',
    nav: [
      { group: 'People', items: [
        { id: 'organizations', label: 'Organizations', icon: 'building' },
        { id: 'profiles', label: 'Profiles', icon: 'users' },
        { id: 'community', label: 'Community', icon: 'users' },
      ] },
      { group: 'Reach', items: [
        { id: 'segments', label: 'Segments', icon: 'segment' },
      ] },
      { group: 'Settings', items: [
        { id: 'settings/profile-types', label: 'Profile Types', icon: 'users' },
      ] },
    ],
  },
  {
    id: 'pipelines',
    label: 'Pipelines',
    sub: 'Author reusable transform graphs that power triggers and automation',
    icon: 'workflow',
    accent: '#38c78e',
    nav: [
      { group: 'Pipelines', items: [
        { id: 'all', label: 'All Pipelines', icon: 'workflow' },
        { id: 'runs', label: 'Run History', icon: 'history' },
        { id: 'secrets', label: 'Secrets', icon: 'key' },
      ] },
    ],
  },
  {
    id: 'experiments',
    label: 'Experiments',
    sub: 'Run A/B tests, toggle feature flags, and measure impact',
    icon: 'flask',
    accent: '#bb2ac9',
    nav: [
      { group: 'Test', items: [
        { id: 'flags', label: 'Feature Flags', icon: 'flag' },
        { id: 'exp', label: 'Experiments', icon: 'beaker' },
        { id: 'layers', label: 'Exclusion Layers', icon: 'columns' },
      ] },
    ],
  },
  {
    id: 'recommendations',
    label: 'Recommendations',
    sub: 'Tune strategies and placements, inspect the ML model, and test personalized feeds',
    icon: 'wand',
    accent: '#84c032',
    nav: [
      { group: 'Overview', items: [
        { id: '', label: 'Overview', icon: 'dashboard' },
      ] },
      { group: 'Manage', items: [
        { id: 'strategies', label: 'Strategies', icon: 'sliders' },
        { id: 'placements', label: 'Placements', icon: 'layers' },
        { id: 'contexts', label: 'Contexts', icon: 'filter' },
      ] },
      { group: 'Inspect', items: [
        { id: 'model', label: 'Models', icon: 'activity' },
      ] },
      { group: 'Test', items: [
        { id: 'test', label: 'Test Console', icon: 'play' },
      ] },
    ],
  },
  {
    id: 'artifacts',
    label: 'Artifacts',
    sub: 'Publish and distribute packages across registries',
    icon: 'package',
    accent: '#f764f6',
    nav: [
      { group: 'Registry', items: [
        { id: 'namespaces', label: 'Namespaces', icon: 'folder' },
        { id: 'repositories', label: 'Repositories', icon: 'boxes' },
      ] },
      { group: 'Settings', items: [
        { id: 'settings/permissions', label: 'Permissions', icon: 'lock' },
      ] },
    ],
  },
  {
    id: 'scripts',
    label: 'Scripts',
    sub: 'Author, validate, and execute Kotlin scripts',
    icon: 'code',
    accent: '#2da0c5',
    nav: [
      { group: 'Library', items: [
        { id: '', label: 'All Scripts', icon: 'list' },
      ] },
    ],
  },
  {
    id: 'commerce',
    label: 'Commerce',
    sub: 'Manage products, catalogs, pricing, and orders',
    icon: 'tag',
    accent: '#a670f4',
    // Hidden + route-blocked when the deployment has the ecommerce module off
    // (ECOMMERCE_ENABLED). Gated in usePersonas().visibleSubsystems and the
    // commerce-admin route middleware.
    requiresFeature: 'ecommerce',
    nav: [
      // More functional groups (products, pricing, stores, …) are added by the
      // later slices as their pages land. The whole subsystem is
      // gated by the `commerce-admin` route middleware + persona visibility.
      { group: 'Overview', items: [
        { id: '', label: 'Overview', icon: 'dashboard' },
      ] },
      { group: 'Catalog', items: [
        { id: 'companies', label: 'Companies', icon: 'building' },
        { id: 'catalogs', label: 'Catalogs', icon: 'boxes' },
        { id: 'manufacturers', label: 'Manufacturers', icon: 'tag' },
        { id: 'products', label: 'Products', icon: 'package' },
        { id: 'catalog-products', label: 'Pricing', icon: 'layers' },
      ] },
      { group: 'Selling', items: [
        { id: 'stores', label: 'Stores', icon: 'store' },
        { id: 'providers', label: 'Providers', icon: 'credit-card' },
      ] },
      { group: 'Fulfillment', items: [
        { id: 'fulfillment', label: 'Fulfillment Centers', icon: 'container' },
        { id: 'inventory', label: 'Inventory', icon: 'archive' },
        { id: 'containers', label: 'Containers', icon: 'package' },
      ] },
      { group: 'Marketing', items: [
        { id: 'promotions', label: 'Promotions', icon: 'megaphone' },
      ] },
      { group: 'Subscriptions', items: [
        { id: 'plans', label: 'Subscription Plans', icon: 'library' },
        { id: 'subscriptions', label: 'Subscriptions', icon: 'refresh' },
      ] },
      { group: 'Customers', items: [
        { id: 'customers', label: 'Customers', icon: 'users' },
        { id: 'accounts', label: 'Billing Accounts', icon: 'user' },
        { id: 'credits', label: 'Store Credit', icon: 'credit-card' },
      ] },
      { group: 'Operations', items: [
        { id: 'orders', label: 'Orders', icon: 'package' },
        { id: 'shipments', label: 'Shipments', icon: 'truck' },
        { id: 'returns', label: 'Returns', icon: 'undo' },
        { id: 'carts', label: 'Carts', icon: 'inspect' },
        { id: 'payments', label: 'Payments', icon: 'credit-card' },
        { id: 'audit', label: 'Audit Log', icon: 'history' },
      ] },
    ],
  },
  {
    id: 'system',
    label: 'System',
    sub: 'Manage jobs, security, storage, and platform configuration',
    icon: 'gear',
    accent: '#ef4444',
    nav: [
      { group: 'Operate', items: [
        { id: 'jobs', label: 'Jobs', icon: 'workflow' },
        { id: 'backups', label: 'Backups', icon: 'database' },
        { id: 'postgres', label: 'PostgreSQL', icon: 'database' },
        { id: 'meilisearch', label: 'Meilisearch', icon: 'search' },
        { id: 'nats', label: 'NATS', icon: 'pulse' },
        { id: 'utilities', label: 'Utilities', icon: 'wrench' },
      ] },
      { group: 'Security', items: [
        { id: 'security/tokens', label: 'API Tokens', icon: 'key' },
        { id: 'security/principals', label: 'Principals', icon: 'users' },
        { id: 'security/groups', label: 'Groups', icon: 'users' },
        { id: 'security/personas', label: 'Personas', icon: 'shield' },
      ] },
      { group: 'Platform', items: [
        { id: 'storage', label: 'Storage', icon: 'database' },
        { id: 'integrations', label: 'Integrations', icon: 'globe' },
        { id: 'packages', label: 'Packages', icon: 'package' },
        { id: 'scheduler', label: 'Scheduler', icon: 'calendar' },
        { id: 'config', label: 'Configuration', icon: 'gear' },
      ] },
    ],
  },
]

export interface SubsystemCategory {
  /** Stable id for the category. */
  id: string
  /** Display label shown as a group header in the navigation modal. */
  label: string
  /** Subsystem ids belonging to this category, in the order they should render. */
  members: string[]
}

/**
 * Groups the subsystems into a small number of labelled categories so the
 * navigation modal can be scanned by region instead of as one flat list.
 * Order here is the order categories render; `members` controls intra-category order.
 */
export const SUBSYSTEM_CATEGORIES: SubsystemCategory[] = [
  { id: 'content', label: 'Content & Experience', members: ['cms', 'feeds', 'localization', 'calendar'] },
  { id: 'audience', label: 'Audience & Reach', members: ['audience', 'communications', 'forms'] },
  { id: 'measure', label: 'Measure', members: ['analytics', 'experiments', 'recommendations'] },
  { id: 'build', label: 'Build & Deliver', members: ['workops', 'git', 'ai', 'scripts', 'artifacts', 'pipelines'] },
  { id: 'commerce', label: 'Commerce', members: ['commerce'] },
  { id: 'operate', label: 'Operate', members: ['gateway', 'kubernetes', 'system'] },
]

export interface CategorizedSubsystems {
  id: string
  label: string
  items: Subsystem[]
}

/**
 * Buckets a list of subsystems into {@link SUBSYSTEM_CATEGORIES}, preserving the
 * declared category and member order. Any subsystem not assigned to a category
 * is surfaced under a trailing "More" group rather than silently dropped, so a
 * newly added subsystem can never disappear from navigation.
 */
export function groupSubsystems(list: Subsystem[]): CategorizedSubsystems[] {
  const byId = new Map(list.map(s => [s.id, s]))
  const assigned = new Set<string>()

  const groups: CategorizedSubsystems[] = SUBSYSTEM_CATEGORIES.map(c => {
    const items: Subsystem[] = []
    for (const id of c.members) {
      const sub = byId.get(id)
      if (sub) {
        items.push(sub)
        assigned.add(id)
      }
    }
    return { id: c.id, label: c.label, items }
  }).filter(c => c.items.length > 0)

  const uncategorized = list.filter(s => !assigned.has(s.id))
  if (uncategorized.length > 0) {
    groups.push({ id: 'more', label: 'More', items: uncategorized })
  }

  return groups
}

export function useSubsystems() {
  return {
    subsystems: SUBSYSTEMS,

    getSubsystem(id: string) {
      return SUBSYSTEMS.find(s => s.id === id)
    },

    defaultPage(sub: string): string {
      const s = SUBSYSTEMS.find(x => x.id === sub)
      return s ? (s.nav[0]?.items[0]?.id || '') : 'dashboard'
    },
  }
}
