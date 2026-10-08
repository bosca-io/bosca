/** Formats event names for display while preserving the GitHub spelling. */
export function pipelineEventLabel(fqdn: string): string {
  const leaf = fqdn.includes('.') ? fqdn.slice(fqdn.lastIndexOf('.') + 1) : fqdn
  return leaf.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/\bGit Hub\b/g, 'GitHub').trim() || fqdn
}
