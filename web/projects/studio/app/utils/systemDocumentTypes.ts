/**
 * Platform modules seed their own document templates and stamp a `type` on
 * them: WorkOps uses "WorkOps Spec" / "WorkOps Requirement" (workops migration
 * V34) and Feeds uses "Feed Item" (feeds migration V4). Those documents are
 * created and managed by their module, not from the CMS, so the CMS leaves
 * them out of its New Document menu and its Documents filter tabs. Template
 * IDs can differ between environments, so match on the type.
 */
export function isSystemDocumentType(type: unknown): boolean {
  return typeof type === 'string' && (type.startsWith('WorkOps ') || type === 'Feed Item')
}
