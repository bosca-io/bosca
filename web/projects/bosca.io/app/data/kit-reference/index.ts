import gettingStarted from './getting-started.md?raw'
import concepts from './concepts.md?raw'
import usingKit from './using-kit.md?raw'
import configuration from './configuration.md?raw'
import kubernetes from './kubernetes.md?raw'
import swarm from './swarm.md?raw'
import compose from './compose.md?raw'
import operations from './operations.md?raw'
import extending from './extending.md?raw'

export const KIT_DOCS = {
  'getting-started': { title: 'Getting Started with Kit', description: 'Understand Kit, choose a deployment path, and follow a first conversation through its lifecycle.', source: gettingStarted },
  'concepts': { title: 'How Kit Works', description: 'The responsibilities, protocols, state, credentials, and recovery boundaries behind a Kit conversation.', source: concepts },
  'using-kit': { title: 'Working with Kit', description: 'Use conversations, context, tools, memories, model choices, approvals, and managed child tasks.', source: usingKit },
  'configuration': { title: 'Kit Configuration', description: 'Configure deployment profiles, private policy, scopes, model choices, context, and runtime limits.', source: configuration },
  'kubernetes': { title: 'Kit on Kubernetes', description: 'Configure the controller, JobProfiles, identity pins, credentials, storage, and private runtime networking.', source: kubernetes },
  'swarm': { title: 'Kit on Docker Swarm', description: 'Run the manager controller and configure isolated runtime Services, Secrets, and overlay networking.', source: swarm },
  'compose': { title: 'Kit and Docker Compose', description: 'Understand what Compose starts and how a Compose installation connects to a managed Kit runtime plane.', source: compose },
  'operations': { title: 'Operating and Supporting Kit', description: 'Plan capacity, monitor lifecycle events, diagnose failures, rotate credentials, back up state, and qualify upgrades.', source: operations },
  'extending': { title: 'Extending Kit', description: 'Customize instructions, add approved MCP connections and Bosca tools, and understand the work involved in another harness or runtime provider.', source: extending }
} satisfies Record<string, { title: string, description: string, source: string }>

export const KIT_DOC_GROUPS = [
  {
    title: 'Understand and Use',
    links: [
      { label: 'Getting Started', to: '/kit-reference/getting-started' },
      { label: 'How Kit Works', to: '/kit-reference/concepts' },
      { label: 'Working with Kit', to: '/kit-reference/using-kit' }
    ]
  },
  {
    title: 'Deploy',
    links: [
      { label: 'Configuration', to: '/kit-reference/configuration' },
      { label: 'Kubernetes', to: '/kit-reference/kubernetes' },
      { label: 'Docker Swarm', to: '/kit-reference/swarm' },
      { label: 'Docker Compose', to: '/kit-reference/compose' }
    ]
  },
  {
    title: 'Operate and Extend',
    links: [
      { label: 'Operations and Support', to: '/kit-reference/operations' },
      { label: 'Extension Points', to: '/kit-reference/extending' }
    ]
  }
]
