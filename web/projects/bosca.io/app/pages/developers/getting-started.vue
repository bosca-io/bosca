<script setup lang="ts">
useSeoMeta({ title: 'Getting Started' })

const topics = [
  {
    title: 'Architecture',
    desc: 'Core/impl split, KSP code generation, and how the pieces fit together.',
    to: '/developers/architecture',
    icon: 'boxes',
    accent: 'var(--brand-accent)'
  },
  {
    title: 'Models & Repositories',
    desc: 'Data classes, @Repository, SQL mapping, and query patterns.',
    to: '/developers/models-and-repositories',
    icon: 'database',
    accent: 'var(--ok)'
  },
  {
    title: 'Services',
    desc: 'Business logic, @ServiceImplementation, injection, and lifecycle.',
    to: '/developers/services',
    icon: 'gear',
    accent: 'var(--warn)'
  },
  {
    title: 'GraphQL',
    desc: '@TypeController, @Field, namespace pattern, queries, mutations, and batching.',
    to: '/developers/graphql',
    icon: 'route',
    accent: 'var(--brand-2)'
  },
  {
    title: 'Routes',
    desc: 'HTTP endpoints, @RouteController, Route, APIRoute, SSERoute, and Pages.',
    to: '/developers/routes',
    icon: 'globe',
    accent: 'var(--info)'
  },
  {
    title: 'Security',
    desc: 'Authentication, credentials, JWT token versioning, OAuth2, passkeys, and API tokens.',
    to: '/developers/security',
    icon: 'lock',
    accent: '#f59e0b'
  },
  {
    title: 'Profile',
    desc: 'User identity, flexible attributes, visibility controls, and the Principal relationship.',
    to: '/developers/profile',
    icon: 'user',
    accent: 'var(--brand-1)'
  },
  {
    title: 'Organizations',
    desc: 'Multi-tenant workspaces, membership, signup tokens, domain auto-join, and org permissions.',
    to: '/developers/organizations',
    icon: 'users',
    accent: '#34d399'
  },
  {
    title: 'Permissions',
    desc: 'PermissibleEntity, evaluators, groups, visibility flags, and the full decision matrix.',
    to: '/developers/permissions',
    icon: 'shield',
    accent: 'var(--err)'
  },
  {
    title: 'Transactions',
    desc: 'Connection lifecycle, savepoints, deferred side effects, and GraphQL connections.',
    to: '/developers/transactions',
    icon: 'workflow',
    accent: '#e879f9'
  },
  {
    title: 'Caching',
    desc: 'CacheManager, ServiceCache, RequestCache, two-tier architecture, and invalidation.',
    to: '/developers/caching',
    icon: 'pulse',
    accent: 'var(--brand-1)'
  },
  {
    title: 'Messaging & Events',
    desc: 'PubSubService, domain events, @JobEvent, dispatch, and job executors.',
    to: '/developers/messaging',
    icon: 'mail',
    accent: '#fb923c'
  },
  {
    title: 'Bulk Processing',
    desc: 'Batch mutations, permission filtering, transactional vs error-tolerant patterns.',
    to: '/developers/bulk-processing',
    icon: 'columns',
    accent: '#a78bfa'
  },
  {
    title: 'Scripting',
    desc: 'Kotlin scripts: event triggers, API endpoints, AI agent tools, sandboxed execution.',
    to: '/developers/scripting',
    icon: 'code',
    accent: '#f472b6'
  },
  {
    title: 'Bosca Git',
    desc: 'Self-hosted Git server, pull requests, CI/CD, script & query source refs.',
    to: '/developers/git',
    icon: 'git-branch',
    accent: '#4ade80'
  },
  {
    title: 'Bosca CLI',
    desc: 'Content management, WorkOps, Kubernetes, and MCP server.',
    to: '/developers/cli',
    icon: 'container',
    accent: 'var(--fg-2)'
  },
  {
    title: 'Infrastructure',
    desc: 'Docker Compose, Kubernetes, downstream systems, and environment configuration.',
    to: '/developers/infrastructure',
    icon: 'building',
    accent: '#38bdf8'
  }
]

const annotations = [
  { name: '@Repository', desc: 'JDBC implementation from interface SQL' },
  { name: '@ServiceImplementation', desc: 'DI provider registration' },
  { name: '@TypeController', desc: 'GraphQL resolver wiring' },
  { name: '@RouteController', desc: 'HTTP route handler + DI provider' },
  { name: '@JobDefinition', desc: 'Job executor + enqueue helpers' },
  { name: '@JobEvent', desc: 'dispatch() for jobs + pub-sub' }
]
</script>

<template>
  <div class="doc-content article">
    <h1>Build on Bosca</h1>
    <p class="subtitle">
      Everything you need to know to build features on the Bosca platform — from data models to
      GraphQL controllers, permissions, caching, and beyond.
    </p>

    <h2 id="explore">
      Explore the Platform
    </h2>
    <div class="topic-grid">
      <NuxtLink
        v-for="topic in topics"
        :key="topic.to"
        :to="topic.to"
        class="topic-card"
        :style="{ '--card-accent': topic.accent }"
      >
        <div class="topic-card-icon">
          <Icon
            :name="topic.icon"
            :size="18"
          />
        </div>
        <h3>{{ topic.title }}</h3>
        <p>{{ topic.desc }}</p>
      </NuxtLink>
    </div>

    <h2 id="annotations">
      Key Annotations
    </h2>
    <p>
      Every annotation is processed by <strong>KSP</strong> at compile time — no runtime reflection.
    </p>
    <div class="anno-grid">
      <div
        v-for="a in annotations"
        :key="a.name"
        class="anno-card"
      >
        <code>{{ a.name }}</code>
        <span class="anno-desc">{{ a.desc }}</span>
      </div>
    </div>

    <h2 id="checklist">
      Quick Start Checklist
    </h2>
    <p>Adding a new feature (e.g., "Tags"):</p>
    <ol>
      <li><strong>Model</strong> — Create <code>Tag</code> and <code>TagInput</code> data classes in the <code>core-*</code> module.</li>
      <li><strong>Database migration</strong> — Create a Flyway migration SQL file.</li>
      <li><strong>Repository</strong> — Create <code>TagRepository</code> interface with <code>@Repository</code> and <code>@Query</code>.</li>
      <li><strong>Service interface</strong> — Create <code>TagService</code> in the <code>core-*</code> module.</li>
      <li><strong>Service impl</strong> — Create <code>TagServiceImpl</code> with <code>@ServiceImplementation</code>.</li>
      <li><strong>GraphQL schema</strong> — Create <code>tags.graphqls</code> under <code>src/main/resources/graphql/</code>.</li>
      <li><strong>Schema registration</strong> — Add <code>@Schema("tags.graphqls")</code> to the module's <code>SchemaRegistrar</code>.</li>
      <li><strong>Type controller</strong> — Create <code>TagController</code> implementing <code>GraphQLController&lt;Tag&gt;</code>.</li>
      <li><strong>Query controller</strong> — Create <code>Tags</code> object and <code>TagsController</code>.</li>
      <li><strong>Mutation controller</strong> — Create <code>TagMutation</code> object and <code>TagMutationController</code>.</li>
      <li><strong>Wire into the tree</strong> — Return namespace objects from parent controllers.</li>
      <li><strong>Events</strong> — Define <code>@JobEvent</code>-annotated event classes.</li>
      <li><strong>Jobs</strong> — Create <code>@JobDefinition</code>-annotated executors.</li>
    </ol>
  </div>
</template>
