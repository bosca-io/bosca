/* eslint-disable @typescript-eslint/no-explicit-any */
import type { AttributeState } from '~/utils/editor/attribute'
import type { Collection, Metadata } from '~/types/graphql'

export type TemplateAttributeTool = {
  name: string | null
  description?: string | null
  query: string
  resultPath?: string | null
}

export async function executeTool(
  item: Metadata | Collection,
  tool: TemplateAttributeTool,
  attribute: AttributeState | null,
  loading: Ref<boolean>,
) {
  const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()

  try {
    loading.value = true
    const executeQuery = tool.query
    const executeResultPath = tool.resultPath
    const variables = item.__typename === 'Metadata'
      ? { metadataId: item.id, version: (item as Metadata).version }
      : { collectionId: item.id }
    const result = executeQuery.startsWith('mutation ')
      ? await gqlMutation<any>(executeQuery, variables)
      : await gqlQuery<any>(executeQuery, variables)
    if (typeof executeResultPath === 'string' && executeResultPath.length > 0) {
      const path = executeResultPath.split('.')
      const resultValue = path.reduce((obj: any, key: any) => {
        if (key.indexOf('[') !== -1) {
          const k = key.substring(0, key.indexOf('['))
          const index = parseInt(key.substring(key.indexOf('[') + 1, key.indexOf(']')))
          return obj[k][index]
        }
        return obj[key]
      }, result)
      if (attribute) {
        switch (attribute.type) {
          case 'COLLECTION':
            if (attribute.list) {
              attribute.collections = resultValue
            } else {
              attribute.collection = resultValue
            }
            break
          case 'DATE':
          case 'DATETIME':
          case 'DATE_TIME':
            attribute.dateTimeValue = resultValue
            break
          case 'FLOAT':
          case 'INT':
            attribute.numberValue = resultValue
            break
          case 'METADATA':
            if (attribute.list) {
              attribute.metadatas = resultValue.map((m: any) => ({
                attributes: m.attributes || { sort: 0 },
                contentType: m.content?.type,
                id: m.id,
                name: m.name,
                relationship: attribute.configuration?.relationship }))
            } else {
              attribute.metadata = {
                attributes: resultValue.attributes || { sort: 0 },
                contentType: resultValue.content?.type,
                id: resultValue.id,
                name: resultValue.name,
                relationship: attribute.configuration?.relationship
              }
            }
            break
          case 'PROFILE':
            break
          default:
            attribute.textValue = resultValue
            break
        }
      }
      return resultValue
    }
  } catch (e: any) {
    console.error('failed to execute tool', e)
    useToast().error(`${tool.name || 'Tool'} failed: ${e?.message || 'unknown error'}`)
  } finally {
    loading.value = false
  }
  return null
}
