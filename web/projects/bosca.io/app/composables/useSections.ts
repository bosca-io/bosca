export interface DocSection {
  id: string
  label: string
  sub: string
  icon: string
  accent: string
  basePath: string
  /**
   * Path to this subsystem's public /discover marketing page, when it has
   * one. Present only for subsystems with a landing page in the family; used
   * to make the subsystem linkable from the capability wall.
   */
  discover?: string
  groups: {
    title: string
    links: { label: string, to: string }[]
  }[]
}

export const SECTIONS: DocSection[] = [
  {
    id: 'studio',
    label: 'Studio',
    sub: 'The admin app — navigation, personas, themes, and shell features',
    icon: 'monitor',
    accent: '#00dc82',
    basePath: '/studio',
    discover: '/discover/studio',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/studio/overview' },
          { label: 'Subsystems', to: '/studio/subsystems' },
          { label: 'First Sign-In', to: '/studio/welcome' }
        ]
      },
      {
        title: 'Sign-In & Accounts',
        links: [
          { label: 'Sign In', to: '/studio/auth/login' },
          { label: 'Sign Up', to: '/studio/auth/signup' },
          { label: 'Verify Email', to: '/studio/auth/verify' },
          { label: 'Forgot & Reset Password', to: '/studio/auth/password-reset' }
        ]
      },
      {
        title: 'Navigation',
        links: [
          { label: 'Sidebar', to: '/studio/sidebar' },
          { label: 'Subsystem Switcher', to: '/studio/subsystem-switcher' },
          { label: 'Command Palette', to: '/studio/command-palette' }
        ]
      },
      {
        title: 'Personalization',
        links: [
          { label: 'Profile Menu', to: '/studio/profile-menu' },
          { label: 'Themes', to: '/studio/themes' },
          { label: 'Personas', to: '/studio/personas' }
        ]
      },
      {
        title: 'Collaboration',
        links: [
          { label: 'Collab Dock', to: '/studio/collab-dock' }
        ]
      },
      {
        title: 'Helm in Studio',
        links: [
          { label: 'Releases', to: '/studio/helm/releases' },
          { label: 'Catalog', to: '/studio/helm/catalog' },
          { label: 'Repositories', to: '/studio/helm/repositories' },
          { label: 'Install Wizard', to: '/studio/helm/install-wizard' }
        ]
      }
    ]
  },
  {
    id: 'developers',
    label: 'Developers',
    sub: 'Architecture, services, GraphQL, and core platform concepts',
    icon: 'code',
    accent: '#3b82f6',
    basePath: '/developers',
    discover: '/discover/developers',
    groups: [
      {
        title: 'Get Started',
        links: [
          { label: 'Overview', to: '/developers/getting-started' },
          { label: 'Run Bosca Locally', to: '/developers/run-locally' },
          { label: 'CLI', to: '/developers/cli' },
          { label: 'Infrastructure', to: '/developers/infrastructure' }
        ]
      },
      {
        title: 'Build on Bosca',
        links: [
          { label: 'Architecture', to: '/developers/architecture' },
          { label: 'Models & Repositories', to: '/developers/models-and-repositories' },
          { label: 'Services', to: '/developers/services' },
          { label: 'GraphQL', to: '/developers/graphql' },
          { label: 'HTTP Routes', to: '/developers/routes' }
        ]
      },
      {
        title: 'Identity & Access',
        links: [
          { label: 'Security', to: '/developers/security' },
          { label: 'Profiles', to: '/developers/profile' },
          { label: 'Organizations', to: '/developers/organizations' },
          { label: 'Permissions', to: '/developers/permissions' }
        ]
      },
      {
        title: 'Runtime',
        links: [
          { label: 'Transactions', to: '/developers/transactions' },
          { label: 'Caching', to: '/developers/caching' },
          { label: 'Messaging & Events', to: '/developers/messaging' },
          { label: 'Bulk Processing', to: '/developers/bulk-processing' },
          { label: 'Scripting', to: '/developers/scripting' },
          { label: 'Git', to: '/developers/git' }
        ]
      }
    ]
  },
  {
    id: 'bible',
    label: 'Bible',
    sub: 'Browse Scripture, manage translations, and link references',
    icon: 'book',
    accent: '#d97706',
    basePath: '/bible',
    discover: '/discover/bible',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/bible/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Translations', to: '/bible/translations' },
          { label: 'Books & Chapters', to: '/bible/books-and-chapters' },
          { label: 'References', to: '/bible/references' },
          { label: 'Import', to: '/bible/import' },
          { label: 'AI Tools', to: '/bible/ai-tools' }
        ]
      }
    ]
  },
  {
    id: 'cms',
    label: 'CMS',
    sub: 'Manage collections, documents, and media assets',
    icon: 'library',
    accent: '#f7828c',
    basePath: '/content',
    discover: '/discover/cms',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/content/overview' }
        ]
      },
      {
        title: 'Library',
        links: [
          { label: 'Collections', to: '/content/collections' },
          { label: 'Metadata', to: '/content/metadata' },
          { label: 'Documents', to: '/content/documents' },
          { label: 'Guides', to: '/content/guides' },
          { label: 'Library Builder', to: '/content/library-builder' },
          { label: 'Data Records', to: '/content/data' },
          { label: 'Bibles', to: '/content/bibles' },
          { label: 'Media', to: '/content/media' },
          { label: 'Supplementary', to: '/content/supplementary' },
          { label: 'Templates', to: '/content/templates' },
          { label: 'Categories & Traits', to: '/content/categories' }
        ]
      },
      {
        title: 'Distribution',
        links: [
          { label: 'Content Health', to: '/content/health' },
          { label: 'States & Transitions', to: '/content/workflows' },
          { label: 'Publishing', to: '/content/publishing' },
          { label: 'Comments & Moderation', to: '/content/comments' }
        ]
      },
      {
        title: 'Settings',
        links: [
          { label: 'Sources', to: '/content/sources' },
          { label: 'Template Tools', to: '/content/template-tools' },
          { label: 'Time Event Types', to: '/content/time-event-types' }
        ]
      }
    ]
  },
  {
    id: 'feeds',
    label: 'Feeds',
    sub: 'Ingest external content feeds and serve them to your audience',
    icon: 'rss',
    accent: '#42daf4',
    basePath: '/feeds',
    discover: '/discover/feeds',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/feeds/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Sources', to: '/feeds/sources' },
          { label: 'Serving & Subscriptions', to: '/feeds/serving' }
        ]
      }
    ]
  },
  {
    id: 'workops',
    label: 'Work Ops',
    sub: 'Plan, track, release, and stay on top of the work that needs you',
    icon: 'kanban',
    accent: '#91a6f6',
    basePath: '/workops',
    discover: '/discover/workops',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/workops/overview' }
        ]
      },
      {
        title: 'Activity',
        links: [
          { label: 'Inbox', to: '/workops/inbox' },
          { label: 'Tasks', to: '/workops/tasks' },
          { label: 'Specs', to: '/workops/specs' },
          { label: 'Requirements', to: '/workops/requirements' },
          { label: 'Filters', to: '/workops/filters' }
        ]
      },
      {
        title: 'Plan',
        links: [
          { label: 'Projects', to: '/workops/projects' },
          { label: 'Portfolios', to: '/workops/portfolios' },
          { label: 'Boards', to: '/workops/boards' },
          { label: 'Sprints', to: '/workops/sprints' },
          { label: 'Milestones', to: '/workops/milestones' },
          { label: 'Workflows', to: '/workops/workflows' }
        ]
      },
      {
        title: 'Settings',
        links: [
          { label: 'Task Types', to: '/workops/task-types' },
          { label: 'Statuses', to: '/workops/statuses' },
          { label: 'Priorities', to: '/workops/priorities' },
          { label: 'Resolutions', to: '/workops/resolutions' },
          { label: 'Labels', to: '/workops/labels' },
          { label: 'Versions', to: '/workops/versions' },
          { label: 'Components', to: '/workops/components' },
          { label: 'Link Types', to: '/workops/link-types' },
          { label: 'Automation', to: '/workops/automation' },
          { label: 'Notifications', to: '/workops/notifications' },
          { label: 'SLA', to: '/workops/sla' },
          { label: 'Permissions', to: '/workops/permissions' }
        ]
      }
    ]
  },
  {
    id: 'git',
    label: 'Git',
    sub: 'Host code, review changes, and run scheduled CI on your infrastructure',
    icon: 'git-branch',
    accent: '#64748b',
    basePath: '/git',
    discover: '/discover/git',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/git/overview' }
        ]
      },
      {
        title: 'Code',
        links: [
          { label: 'Repositories', to: '/git/repositories' },
          { label: 'Pull Requests', to: '/git/pull-requests' },
          { label: 'Search', to: '/git/search' },
          { label: 'Compare', to: '/git/compare' },
          { label: 'Commit Statuses', to: '/git/statuses' }
        ]
      },
      {
        title: 'CI/CD',
        links: [
          { label: 'Pipelines', to: '/git/pipelines' },
          { label: 'CI Agents', to: '/git/ci-agents' }
        ]
      },
      {
        title: 'Settings',
        links: [
          { label: 'Branch Protection', to: '/git/branch-protection' },
          { label: 'Webhooks', to: '/git/webhooks' }
        ]
      }
    ]
  },
  {
    id: 'kubernetes',
    label: 'Kubernetes',
    sub: 'Monitor clusters, manage workloads, and configure infrastructure',
    icon: 'kubernetes',
    accent: '#5e8df4',
    basePath: '/kubernetes',
    discover: '/discover/kubernetes',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/kubernetes/overview' }
        ]
      },
      {
        title: 'Cluster',
        links: [
          { label: 'Cluster Registration', to: '/kubernetes/clusters' },
          { label: 'Nodes', to: '/kubernetes/nodes' },
          { label: 'Events', to: '/kubernetes/events' },
          { label: 'Namespaces', to: '/kubernetes/namespaces' }
        ]
      },
      {
        title: 'Workloads',
        links: [
          { label: 'Workloads & Resources', to: '/kubernetes/workloads' },
          { label: 'Pods', to: '/kubernetes/pods' },
          { label: 'Jobs & CronJobs', to: '/kubernetes/jobs' }
        ]
      },
      {
        title: 'Traffic',
        links: [
          { label: 'Networking', to: '/kubernetes/network' },
          { label: 'Gateway API', to: '/kubernetes/gateways' }
        ]
      },
      {
        title: 'Config',
        links: [
          { label: 'Config & Secrets', to: '/kubernetes/config' },
          { label: 'Storage', to: '/kubernetes/storage' },
          { label: 'Custom Resources', to: '/kubernetes/crds' },
          { label: 'Access Control', to: '/kubernetes/access' }
        ]
      },
      {
        title: 'Add-ons',
        links: [
          { label: 'cert-manager', to: '/kubernetes/certs' },
          { label: 'CloudNativePG', to: '/kubernetes/cnpg' }
        ]
      },
      {
        title: 'Architecture',
        links: [
          { label: 'Kubernetes Controller', to: '/kubernetes/controller' },
          { label: 'Streaming & Subscriptions', to: '/kubernetes/streaming' },
          { label: 'Security Model', to: '/kubernetes/security' }
        ]
      }
    ]
  },
  {
    id: 'helm',
    label: 'Helm',
    sub: 'Deploy Bosca using the official Helm charts',
    icon: 'archive',
    accent: '#0f7fff',
    basePath: '/helm',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/helm/overview' },
          { label: 'Chart Catalog', to: '/helm/bosca-charts' }
        ]
      },
      {
        title: 'Architecture',
        links: [
          { label: 'How the Charts Compose', to: '/helm/architecture' },
          { label: 'Versioning Policy', to: '/helm/versioning' }
        ]
      },
      {
        title: 'Deploying',
        links: [
          { label: 'Installing Bosca', to: '/helm/install' },
          { label: 'Upgrading Bosca', to: '/helm/upgrade' },
          { label: 'Values & Customization', to: '/helm/values' }
        ]
      }
    ]
  },
  {
    id: 'ai',
    label: 'AI',
    sub: 'Work with Kit, build agents, and turn questions into reusable results',
    icon: 'wand',
    accent: '#f9b18f',
    basePath: '/ai',
    discover: '/discover/ai',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/ai/overview' }
        ]
      },
      {
        title: 'Kit',
        links: [
          { label: 'Chat', to: '/ai/chat' },
          { label: 'History', to: '/ai/history' }
        ]
      },
      {
        title: 'Build',
        links: [
          { label: 'Agents', to: '/ai/agents' },
          { label: 'Agent Tools', to: '/ai/agent-tools' },
          { label: 'Prompts', to: '/ai/prompts' }
        ]
      },
      {
        title: 'Infrastructure',
        links: [
          { label: 'Models', to: '/ai/models' },
          { label: 'MCP Servers', to: '/ai/mcp' }
        ]
      }
    ]
  },
  {
    id: 'analytics',
    label: 'Analytics',
    sub: 'Track events, investigate data, and build fast, transparent dashboards',
    icon: 'pulse',
    accent: '#268e71',
    basePath: '/analytics',
    discover: '/discover/analytics',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/analytics/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Queries', to: '/analytics/queries' },
          { label: 'Dashboards', to: '/analytics/dashboards' },
          { label: 'Visualizations', to: '/analytics/visualizations' },
          { label: 'Ingestion', to: '/analytics/ingestion' },
          { label: 'Error Tracking', to: '/analytics/error-tracking' }
        ]
      }
    ]
  },
  {
    id: 'experiments',
    label: 'Experiments',
    sub: 'Run A/B tests, toggle feature flags, and measure impact',
    icon: 'flask',
    accent: '#bb2ac9',
    basePath: '/experiments',
    discover: '/discover/experiments',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/experiments/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Feature Flags', to: '/experiments/feature-flags' },
          { label: 'A/B Experiments', to: '/experiments/experiments' },
          { label: 'Exclusion Layers', to: '/experiments/exclusion-layers' },
          { label: 'Rollout Policies', to: '/experiments/rollout-policies' },
          { label: 'Results & Analysis', to: '/experiments/results' }
        ]
      }
    ]
  },
  {
    id: 'recommendations',
    label: 'Recommendations',
    sub: 'Tune strategies and placements, inspect the ML model, and test personalized feeds',
    icon: 'wand',
    accent: '#84c032',
    basePath: '/recommendations',
    discover: '/discover/recommendations',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/recommendations/overview' }
        ]
      },
      {
        title: 'Manage',
        links: [
          { label: 'Strategies', to: '/recommendations/strategies' },
          { label: 'Placements', to: '/recommendations/placements' },
          { label: 'Personalization Signals', to: '/recommendations/signals' }
        ]
      },
      {
        title: 'Model',
        links: [
          { label: 'Model & Training', to: '/recommendations/model' },
          { label: 'Testing', to: '/recommendations/testing' }
        ]
      }
    ]
  },
  {
    id: 'audience',
    label: 'Audience',
    sub: 'Organize profiles, build segments, and run campaigns',
    icon: 'target',
    accent: '#da9e30',
    basePath: '/audience',
    discover: '/discover/audience',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/audience/overview' }
        ]
      },
      {
        title: 'People',
        links: [
          { label: 'Profiles', to: '/audience/profiles' },
          { label: 'Organizations', to: '/audience/organizations' },
          { label: 'Community', to: '/audience/community' }
        ]
      },
      {
        title: 'Reach',
        links: [
          { label: 'Segments', to: '/audience/segments' },
          { label: 'Campaigns', to: '/audience/campaigns' }
        ]
      },
      {
        title: 'Settings',
        links: [
          { label: 'Profile Types', to: '/audience/profile-types' },
          { label: 'States', to: '/audience/states' },
          { label: 'Transitions', to: '/audience/transitions' }
        ]
      }
    ]
  },
  {
    id: 'localization',
    label: 'Localization',
    sub: 'Generate, review, and ship product language across every platform',
    icon: 'languages',
    accent: '#49e93c',
    basePath: '/localization',
    discover: '/discover/localization',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/localization/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Projects', to: '/localization/projects' },
          { label: 'Strings & Translations', to: '/localization/strings' },
          { label: 'Documents', to: '/localization/documents' },
          { label: 'Workflow', to: '/localization/workflow' },
          { label: 'Export & Sync', to: '/localization/export' }
        ]
      }
    ]
  },
  {
    id: 'forms',
    label: 'Forms',
    sub: 'Build forms, capture responses, and manage submissions',
    icon: 'form',
    accent: '#f6399c',
    basePath: '/forms',
    discover: '/discover/forms',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/forms/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Schemas', to: '/forms/schemas' },
          { label: 'Controls', to: '/forms/controls' },
          { label: 'Validation', to: '/forms/validation' },
          { label: 'Submissions', to: '/forms/submissions' },
          { label: 'Builder', to: '/forms/builder' }
        ]
      }
    ]
  },
  {
    id: 'calendar',
    label: 'Calendar',
    sub: 'Schedule events and coordinate timelines',
    icon: 'calendar',
    accent: '#7659f2',
    basePath: '/calendar',
    discover: '/discover/calendar',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/calendar/overview' }
        ]
      },
      {
        title: 'Features',
        links: [
          { label: 'Calendars', to: '/calendar/calendars' },
          { label: 'Events', to: '/calendar/events' },
          { label: 'Participants', to: '/calendar/participants' },
          { label: 'Recurrence', to: '/calendar/recurrence' }
        ]
      }
    ]
  },
  {
    id: 'communications',
    label: 'Communications',
    sub: 'Chat in real time and deliver localized email and push with care',
    icon: 'message',
    accent: '#2272f2',
    basePath: '/communications',
    discover: '/discover/communications',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/communications/overview' }
        ]
      },
      {
        title: 'Chat',
        links: [
          { label: 'Channels', to: '/communications/channels' }
        ]
      },
      {
        title: 'Email',
        links: [
          { label: 'Delivery Status', to: '/communications/delivery' },
          { label: 'Preferences', to: '/communications/preferences' },
          { label: 'Suppression List', to: '/communications/suppression' }
        ]
      }
    ]
  },
  {
    id: 'gateway',
    label: 'Gateway',
    sub: 'Proxy authenticated traffic to upstream HTTP services',
    icon: 'globe',
    accent: '#c3cf3a',
    basePath: '/gateway',
    discover: '/discover/gateway',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/gateway/overview' }
        ]
      },
      {
        title: 'Topology',
        links: [
          { label: 'Gateways', to: '/gateway/gateways' },
          { label: 'Routes', to: '/gateway/routes' }
        ]
      }
    ]
  },
  {
    id: 'artifacts',
    label: 'Artifacts',
    sub: 'Publish and distribute packages across registries',
    icon: 'package',
    accent: '#f764f6',
    basePath: '/artifacts',
    discover: '/discover/artifacts',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/artifacts/overview' }
        ]
      },
      {
        title: 'Registry',
        links: [
          { label: 'Namespaces', to: '/artifacts/namespaces' },
          { label: 'Repositories', to: '/artifacts/repositories' },
          { label: 'Versions & Tags', to: '/artifacts/versions' }
        ]
      },
      {
        title: 'Settings',
        links: [
          { label: 'Permissions', to: '/artifacts/permissions' }
        ]
      }
    ]
  },
  {
    id: 'scripts',
    label: 'Scripts',
    sub: 'Author, validate, and execute Kotlin scripts',
    icon: 'code',
    accent: '#2da0c5',
    basePath: '/scripts',
    discover: '/discover/scripts',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/scripts/overview' }
        ]
      },
      {
        title: 'Authoring',
        links: [
          { label: 'Script Library', to: '/scripts/library' },
          { label: 'Triggers', to: '/scripts/triggers' },
          { label: 'API Endpoints', to: '/scripts/endpoints' }
        ]
      }
    ]
  },
  {
    id: 'pipelines',
    label: 'Pipelines',
    sub: 'Build durable automations visually or with Kit, from events or schedules',
    icon: 'workflow',
    accent: '#38c78e',
    basePath: '/pipelines',
    discover: '/discover/pipelines',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/pipelines/overview' }
        ]
      },
      {
        title: 'Building',
        links: [
          { label: 'Pipeline Editor', to: '/pipelines/editor' },
          { label: 'Node Reference', to: '/pipelines/nodes' },
          { label: 'Dry Runs', to: '/pipelines/dry-runs' }
        ]
      },
      {
        title: 'Operate',
        links: [
          { label: 'Triggers & Runs', to: '/pipelines/triggers' }
        ]
      }
    ]
  },
  {
    id: 'bml',
    label: 'BML',
    sub: 'Build localized, server-rendered sites and transactional email',
    icon: 'braces',
    accent: '#38bdf8',
    basePath: '/bml',
    discover: '/discover/bml',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/bml/overview' }
        ]
      },
      {
        title: 'Authoring',
        links: [
          { label: 'Pages & Components', to: '/bml/pages-and-components' },
          { label: 'Live State & Actions', to: '/bml/live-state' },
          { label: 'Data & Deployment', to: '/bml/data-and-deployment' }
        ]
      }
    ]
  },
  {
    id: 'commerce',
    label: 'Commerce',
    sub: 'Manage products, catalogs, pricing, and orders',
    icon: 'tag',
    accent: '#a670f4',
    basePath: '/commerce',
    discover: '/discover/commerce',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/commerce/overview' }
        ]
      },
      {
        title: 'Sell',
        links: [
          { label: 'Stores & Catalogs', to: '/commerce/stores' },
          { label: 'Products & Pricing', to: '/commerce/products' },
          { label: 'Promotions', to: '/commerce/promotions' }
        ]
      },
      {
        title: 'Orders',
        links: [
          { label: 'Carts & Orders', to: '/commerce/carts-and-orders' },
          { label: 'Returns & Refunds', to: '/commerce/returns' }
        ]
      },
      {
        title: 'Fulfillment',
        links: [
          { label: 'Inventory & Centers', to: '/commerce/fulfillment' },
          { label: 'Shipments & Packing', to: '/commerce/shipments' }
        ]
      },
      {
        title: 'Billing',
        links: [
          { label: 'Subscriptions & Plans', to: '/commerce/subscriptions' },
          { label: 'Customers & Accounts', to: '/commerce/customers' }
        ]
      },
      {
        title: 'Settings',
        links: [
          { label: 'Providers', to: '/commerce/providers' },
          { label: 'Audit Log', to: '/commerce/audit' }
        ]
      }
    ]
  },
  {
    id: 'system',
    label: 'System',
    sub: 'Manage jobs, security, storage, and platform configuration',
    icon: 'gear',
    accent: '#ef4444',
    basePath: '/system',
    discover: '/discover/system',
    groups: [
      {
        title: 'Getting Started',
        links: [
          { label: 'Overview', to: '/system/overview' }
        ]
      },
      {
        title: 'Operate',
        links: [
          { label: 'Jobs', to: '/system/jobs' },
          { label: 'Health Check', to: '/system/health-check' },
          { label: 'Backups', to: '/system/backups' },
          { label: 'PostgreSQL', to: '/system/postgres' },
          { label: 'Meilisearch', to: '/system/meilisearch' },
          { label: 'NATS', to: '/system/nats' }
        ]
      },
      {
        title: 'Security',
        links: [
          { label: 'API Tokens', to: '/system/tokens' },
          { label: 'Principals', to: '/system/principals' },
          { label: 'Groups', to: '/system/groups' },
          { label: 'Personas', to: '/system/personas' },
          { label: 'Passkeys', to: '/system/passkeys' },
          { label: 'Security Overview', to: '/system/security' }
        ]
      },
      {
        title: 'Configure',
        links: [
          { label: 'Storage', to: '/system/storage' },
          { label: 'Integrations', to: '/system/integrations' },
          { label: 'Scheduler', to: '/system/scheduler' },
          { label: 'Configuration', to: '/system/configuration' }
        ]
      }
    ]
  }
]

export interface SectionCategory {
  /** Stable id for the category. */
  id: string
  /** Display label shown as a group header in navigation. */
  label: string
  /** Section ids belonging to this category, in the order they should render. */
  members: string[]
}

/**
 * Groups the doc sections into labelled categories so the navigation modal and
 * the home grid can be scanned by region. The subsystem categories mirror
 * Studio's `SUBSYSTEM_CATEGORIES`; "Platform & Guides" holds the cross-cutting
 * sections (the Studio guide, developer docs, Helm) that are not subsystems.
 */
export const SECTION_CATEGORIES: SectionCategory[] = [
  { id: 'platform', label: 'Platform & Guides', members: ['developers'] },
  { id: 'content', label: 'Content & Experience', members: ['cms', 'feeds', 'bml', 'localization', 'calendar', 'bible'] },
  { id: 'audience', label: 'Audience & Reach', members: ['audience', 'communications', 'forms'] },
  { id: 'measure', label: 'Measure', members: ['analytics', 'experiments', 'recommendations'] },
  { id: 'build', label: 'Build & Deliver', members: ['workops', 'git', 'ai', 'scripts', 'pipelines', 'artifacts'] },
  { id: 'commerce', label: 'Commerce', members: ['commerce'] },
  { id: 'operate', label: 'Operate', members: ['gateway', 'kubernetes', 'system'] },
  { id: 'administer', label: 'Administer', members: ['studio', 'helm'] }
]

export interface CategorizedSections {
  id: string
  label: string
  items: DocSection[]
}

/**
 * Buckets sections into {@link SECTION_CATEGORIES}, preserving declared category
 * and member order. Any section not assigned to a category is surfaced under a
 * trailing "More" group rather than silently dropped.
 */
export function groupSections(list: DocSection[]): CategorizedSections[] {
  const byId = new Map(list.map(s => [s.id, s]))
  const assigned = new Set<string>()

  const groups: CategorizedSections[] = SECTION_CATEGORIES.map((c) => {
    const items: DocSection[] = []
    for (const id of c.members) {
      const sec = byId.get(id)
      if (sec) {
        items.push(sec)
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

const BOSCA_BASE_HUE = 140

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

export function accentHueShift(accent: string): number {
  return hexToHue(accent) - BOSCA_BASE_HUE
}

export function useSections() {
  const route = useRoute()

  const currentSection = computed(() => {
    const path = route.path
    return SECTIONS.find(s => path === s.basePath || path.startsWith(`${s.basePath}/`)) || null
  })

  const hueShift = computed(() => currentSection.value ? accentHueShift(currentSection.value.accent) : 0)

  return {
    sections: SECTIONS.filter(section => section.id === 'developers'),
    currentSection,
    hueShift
  }
}
