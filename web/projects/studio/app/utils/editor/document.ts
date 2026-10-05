/* eslint-disable @typescript-eslint/no-explicit-any */
export function normalizeDocumentReferences(references: unknown): string[] {
  if (!Array.isArray(references)) return []
  return references.filter((reference): reference is string =>
    typeof reference === 'string' && reference.length > 0
  )
}

export function sanitizeDocument(document: any) {
  if (!document || typeof document !== 'object') return document

  const newNode = { ...document }
  if (
    document.type === 'container'
    && document.attrs
    && typeof document.attrs === 'object'
    && 'references' in document.attrs
    && document.attrs.references !== null
    && document.attrs.references !== undefined
  ) {
    newNode.attrs = {
      ...document.attrs,
      references: normalizeDocumentReferences(document.attrs.references),
    }
  }

  if (!Array.isArray(document.content) || document.content.length === 0) return newNode

  newNode.content = document.content
    .filter((child: any) => child.type !== 'text' || !!child.text?.length)
    .map((child: any) => sanitizeDocument(child))

  return newNode
}
