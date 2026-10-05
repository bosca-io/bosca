/**
 * Icon + accent for a pipeline node, shown on canvas cards, in the editor palette, and in the Node
 * Browser. The backend node registry carries key/label/category/group (the key is the node's
 * serialization discriminator), so presentation is resolved client-side: exact key first, then a
 * verb suffix (the repeated setter/lifecycle verbs across domains), then the key's domain prefix
 * (keys are namespaced, e.g. `collection.items`), then category. Domain icons follow the subsystem
 * nav in useSubsystems (tasks=check, specs=file-text, profiles=user, …) so the same entity reads
 * the same everywhere. Every name here must exist in the shared Icon component's lucide map.
 */

const KIND_ICONS: Record<string, string> = {
  // Core — flow & structure
  'input': 'play',
  'output': 'target',
  'condition': 'split',
  'switch': 'route',
  'forEach': 'repeat',
  'runPipeline': 'workflow',
  // Core — transforms
  'jsonata': 'wand',
  'objectsToMap': 'merge',
  'jsonToSerializable': 'package',
  'serializableToJson': 'braces',
  'flatten': 'layers',
  'getId': 'fingerprint',
  'toUuid': 'hash',
  'toString': 'type',
  'cast': 'arrow-right-left',
  // Core — gates & timing
  'delay': 'timer',
  'waitUntil': 'clock',
  'waitForInput': 'hourglass',
  'gate.approval': 'shield-check',
  // Core — actions & messaging
  'dispatchEvent': 'megaphone',
  'executeJob': 'hammer',
  'executeScript': 'code',
  'throw': 'alert',
  'sendEmail': 'mail',
  'sendSlack': 'message',
  'sendWebhook': 'globe',

  // Content
  'convertToDocument': 'file-plus',
  'metadata.document': 'file-text',
  'metadata.embed': 'brain',
  'metadata.summarize': 'align-left',
  'metadata.supplementaries': 'paperclip',
  'collection.items': 'list',
  'content.visibilityGate': 'eye',
  'getComment': 'message-square',
  'setCommentStatus': 'message-square',
  'moderateText': 'shield',
  'evaluateTextModeration': 'gavel',
  'verdictToCommentStatus': 'scale',
  'search.buildMetadataDocument': 'file-search',
  'search.buildCollectionDocument': 'file-search',
  'search.buildProfileDocument': 'file-search',
  'search.index': 'search',
  'search.indexDocument': 'file-search',
  'search.remove': 'search-x',

  // WorkOps — releases & environments
  'release.tag': 'tag',
  'release.markReleased': 'flag',
  'release.projectVersions': 'git-commit',
  'environment.createDeployment': 'upload',
  'environment.createPromotion': 'trending-up',
  'environment.markDeployed': 'check',
  'release.markDeployed': 'check',
  'environment.waitHealthy': 'heart-pulse',
  // WorkOps — builds
  'release.commit': 'git-commit',
  'release.triggerBuild': 'hammer',
  'release.buildWait': 'hourglass',
  'release.getBuildRun': 'history',
  // WorkOps — artifacts
  'release.associateArtifact': 'package-plus',
  'release.producedArtifacts': 'package-search',
  'release.detectArtifactType': 'scan-line',
  'release.useArtifact': 'package-open',
  'release.publishWait': 'hourglass',
  'artifact.select': 'package-check',
  // WorkOps — app stores
  'release.playRollout': 'smartphone',
  'release.distributePlay': 'share-2',
  // Kubernetes (channel adapters share the release.* namespace)
  'release.deploy': 'rocket',
  'release.promote': 'trending-up',
  'release.rollback': 'undo-2',
  'release.helmStatus': 'kubernetes',

  // Social
  'profile.getAttribute': 'user-search',
  'profile.setAttribute': 'user-cog',

  // Recommendations
  'recommendations.classify': 'brain',
  'recommendations.computeProfileSignals': 'activity',
  'recommendations.inferInterest': 'sparkles',

  // HubSpot
  'hubspotAdd': 'user-plus',
  'hubspotUpdate': 'user-cog',
  'hubspotGetId': 'fingerprint',
  'hubspotAddProfileAttribute': 'stamp',
  'hubspotAddToList': 'list-plus',
  'hubspotAddToConfiguredLists': 'list-plus',
  'hubspotAssociateMember': 'link',
  'hubspotAssociateMemberships': 'link',
  'hubspotRemoveMember': 'user-minus',
  'hubspotSendEvent': 'send',
  'hubspotSubscribe': 'bell-plus',
  'hubspotSubscribeToConfigured': 'bell-plus',
  'hubspotSyncRoute': 'refresh',
  'hubspotProperties': 'wrench',
}

/** The lifecycle/setter verbs repeated across domains (`collection.setPublic`, `metadata.setPublic`, …). */
const VERB_ICONS: Record<string, string> = {
  delete: 'trash',
  setPublic: 'eye',
  setReady: 'check',
  setSearchable: 'search',
  setRecommendable: 'sparkles',
  setAttributes: 'tag',
  transition: 'arrow-right-left',
}

const DOMAIN_ICONS: Record<string, string> = {
  collection: 'boxes',
  metadata: 'file',
  profile: 'user',
  organization: 'building',
  task: 'check',
  spec: 'file-text',
  requirement: 'list-checks',
  environment: 'server',
  release: 'rocket',
  version: 'git-commit',
  project: 'folder',
  sprint: 'calendar',
  search: 'search',
  recommendations: 'sparkles',
  artifact: 'package',
}

const CATEGORY_ICONS: Record<string, string> = {
  INPUT: 'play',
  OUTPUT: 'target',
  TRANSFORM: 'wand',
  FETCH: 'database',
  COMBINE: 'merge',
  ROUTE: 'split',
  ACTION: 'pickaxe',
}

export function pipelineNodeIcon(kind: string, category: string): string {
  const exact = KIND_ICONS[kind]
  if (exact) return exact
  const dot = kind.indexOf('.')
  if (dot > 0) {
    const verb = VERB_ICONS[kind.slice(dot + 1)]
    if (verb) return verb
    const domain = DOMAIN_ICONS[kind.slice(0, dot)]
    if (domain) return domain
  }
  return CATEGORY_ICONS[category] ?? 'puzzle'
}

/**
 * The node's accent color, keyed by its functional category — the same hue on the canvas card's
 * border, the palette icon, and the Node Browser, so a node's kind reads consistently everywhere.
 * Project repositories use these as `--accent` custom-property values, so the fallbacks must be
 * literal colors (a bare `var(--accent)` default would be self-referential and invalid).
 */
export function pipelineNodeAccent(category: string): string {
  switch (category) {
    case 'INPUT': return 'var(--success, #34d399)'
    case 'OUTPUT': return 'var(--info, #60a5fa)'
    case 'ACTION': return 'var(--warning, #fbbf24)'
    case 'TRANSFORM': return '#818cf8'
    case 'COMBINE': return '#c084fc'
    case 'FETCH': return '#f472b6'
    case 'ROUTE': return '#22d3ee'
    default: return 'var(--text-muted, #94a3b8)'
  }
}
