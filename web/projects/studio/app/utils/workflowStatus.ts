export const WORKFLOW_STATUS: Record<string, { label: string; color: string }> = {
  draft: { label: 'Draft', color: '#6c7388' },
  review: { label: 'Review', color: '#5ec5ff' },
  translate: { label: 'Translation', color: '#ffb547' },
  scheduled: { label: 'Scheduled', color: '#a78bff' },
  published: { label: 'Published', color: '#2a9d6e' },
  archived: { label: 'Archived', color: '#3a4256' },
}

export function getWorkflowBadge(state: string): { label: string; color: string } {
  return WORKFLOW_STATUS[state] ?? { label: state, color: '#6c7388' }
}

export function getWorkflowState(workflow: { state: string; pending?: string | null } | null | undefined): string {
  if (!workflow) return 'draft'
  return workflow.pending ?? workflow.state ?? 'draft'
}
