export const WORKOPS_PROJECT_ATTRIBUTE = 'workopsProjectId'
export const NO_WORKOPS_PROJECT = '__no_workops_project__'

function jsonObject(attributes: unknown): Record<string, unknown> {
  if (attributes === null || typeof attributes !== 'object' || Array.isArray(attributes)) return {}
  return attributes as Record<string, unknown>
}

export function linkedWorkOpsProjectId(attributes: unknown): string | null {
  const value = jsonObject(attributes)[WORKOPS_PROJECT_ATTRIBUTE]
  return typeof value === 'string' && value.length > 0 ? value : null
}

export function withWorkOpsProjectId(attributes: unknown, projectId: string | null): Record<string, unknown> | null {
  const { [WORKOPS_PROJECT_ATTRIBUTE]: _existingBinding, ...unrelatedAttributes } = jsonObject(attributes)
  const next = projectId
    ? { ...unrelatedAttributes, [WORKOPS_PROJECT_ATTRIBUTE]: projectId }
    : unrelatedAttributes
  return Object.keys(next).length > 0 ? next : null
}
