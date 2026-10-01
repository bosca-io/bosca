import type { FormSchema, FormSchemaInput, FormSchemaPermission, FormSubmissionInput, SubmittedForm } from './types'
import { graphqlFetch } from './graphql-fetch'

class GraphQLError extends Error {
  constructor(message: string, public errors?: unknown[]) {
    super(message)
    this.name = 'GraphQLError'
  }
}

class NetworkError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NetworkError'
  }
}

/**
 * Sends a GraphQL request using native fetch. Throws on errors,
 * unlike {@link graphqlFetch} which returns result objects.
 * Delegates to graphqlFetch for the actual network call.
 */
async function graphqlRequest<T>(
  apiUrl: string,
  query: string,
  variables: Record<string, unknown>,
  token?: string,
): Promise<T> {
  const result = await graphqlFetch<T>({ apiUrl, query, variables, token })
  if (result.error) {
    throw new GraphQLError(result.error)
  }
  return result.data as T
}

// ---------------------------------------------------------------------------
// Queries & Mutations
// ---------------------------------------------------------------------------

const FORM_SCHEMA_FIELDS = `
  id type key name description schema uiSchema configuration version public published
  profileMapping { nameField visibility attributes { typeId field attributeKey } }
  permissions { action groupId group { id name } }
  created modified
`

const GET_FORM_SCHEMA_BY_KEY = `
  query GetFormSchemaByKey($key: String!) {
    formSchemas {
      byKey(key: $key) {
        ${FORM_SCHEMA_FIELDS}
      }
    }
  }
`

const GET_FORM_SCHEMA_BY_ID = `
  query GetFormSchemaById($id: UUID!) {
    formSchemas {
      byId(id: $id) {
        ${FORM_SCHEMA_FIELDS}
      }
    }
  }
`

const GET_ALL_FORM_SCHEMAS = `
  query GetAllFormSchemas {
    formSchemas {
      all {
        ${FORM_SCHEMA_FIELDS}
      }
    }
  }
`

const SAVE_FORM_SCHEMA = `
  mutation SaveFormSchema($input: FormSchemaInput!) {
    formSchemasMutation {
      save(input: $input) {
        ${FORM_SCHEMA_FIELDS}
      }
    }
  }
`

const DELETE_FORM_SCHEMA = `
  mutation DeleteFormSchema($id: UUID!) {
    formSchemasMutation {
      delete(id: $id)
    }
  }
`

const SET_FORM_SCHEMA_PUBLISHED = `
  mutation SetFormSchemaPublished($id: UUID!, $published: Boolean!) {
    formSchemasMutation {
      setPublished(id: $id, published: $published) {
        ${FORM_SCHEMA_FIELDS}
      }
    }
  }
`

const ADD_FORM_SCHEMA_PERMISSION = `
  mutation AddFormSchemaPermission($permission: PermissionInput!) {
    formSchemasMutation {
      addPermission(permission: $permission) {
        action groupId group { id name }
      }
    }
  }
`

const DELETE_FORM_SCHEMA_PERMISSION = `
  mutation DeleteFormSchemaPermission($permission: PermissionInput!) {
    formSchemasMutation {
      deletePermission(permission: $permission) {
        action groupId group { id name }
      }
    }
  }
`

const SUBMIT_FORM = `
  mutation SubmitForm($input: FormSubmissionInput!) {
    forms {
      submit(input: $input) {
        id type
      }
    }
  }
`

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Fetches a form schema by its unique key.
 * Does not require authentication — schemas are public for rendering.
 */
export async function getFormSchemaByKey(
  apiUrl: string,
  key: string,
  token?: string,
): Promise<FormSchema | null> {
  type R = { formSchemas: { byKey: FormSchema | null } }
  const data = await graphqlRequest<R>(apiUrl, GET_FORM_SCHEMA_BY_KEY, { key }, token)
  return data.formSchemas.byKey
}

/**
 * Fetches a form schema by its UUID.
 */
export async function getFormSchemaById(
  apiUrl: string,
  id: string,
  token?: string,
): Promise<FormSchema | null> {
  type R = { formSchemas: { byId: FormSchema | null } }
  const data = await graphqlRequest<R>(apiUrl, GET_FORM_SCHEMA_BY_ID, { id }, token)
  return data.formSchemas.byId
}

/**
 * Fetches all form schemas. Requires editor permissions.
 */
export async function getAllFormSchemas(
  apiUrl: string,
  token?: string,
): Promise<FormSchema[]> {
  type R = { formSchemas: { all: FormSchema[] } }
  const data = await graphqlRequest<R>(apiUrl, GET_ALL_FORM_SCHEMAS, {}, token)
  return data.formSchemas.all
}

/**
 * Creates or updates a form schema (upserts by key). Requires editor permissions.
 */
export async function saveFormSchema(
  apiUrl: string,
  input: FormSchemaInput,
  token?: string,
): Promise<FormSchema> {
  type R = { formSchemasMutation: { save: FormSchema } }
  const data = await graphqlRequest<R>(apiUrl, SAVE_FORM_SCHEMA, { input }, token)
  return data.formSchemasMutation.save
}

/**
 * Deletes a form schema by ID. Requires admin permissions.
 */
export async function deleteFormSchema(
  apiUrl: string,
  id: string,
  token?: string,
): Promise<boolean> {
  type R = { formSchemasMutation: { delete: boolean } }
  const data = await graphqlRequest<R>(apiUrl, DELETE_FORM_SCHEMA, { id }, token)
  return data.formSchemasMutation.delete
}

/**
 * Submits form data. Works without authentication for anonymous submissions
 * (backend rate-limits at 10/min per IP).
 */
export async function submitForm(
  apiUrl: string,
  input: FormSubmissionInput,
  token?: string,
): Promise<SubmittedForm> {
  type R = { forms: { submit: SubmittedForm } }
  const data = await graphqlRequest<R>(apiUrl, SUBMIT_FORM, { input }, token)
  return data.forms.submit
}

/**
 * Sets the published state of a form schema.
 */
export async function setFormSchemaPublished(
  apiUrl: string,
  id: string,
  published: boolean,
  token?: string,
): Promise<FormSchema> {
  type R = { formSchemasMutation: { setPublished: FormSchema } }
  const data = await graphqlRequest<R>(apiUrl, SET_FORM_SCHEMA_PUBLISHED, { id, published }, token)
  return data.formSchemasMutation.setPublished
}

/**
 * Adds a permission grant to a form schema.
 */
export async function addFormSchemaPermission(
  apiUrl: string,
  entityId: string,
  groupId: string,
  action: string,
  token?: string,
): Promise<FormSchemaPermission> {
  type R = { formSchemasMutation: { addPermission: FormSchemaPermission } }
  const data = await graphqlRequest<R>(
    apiUrl,
    ADD_FORM_SCHEMA_PERMISSION,
    { permission: { entityId, groupId, action } },
    token,
  )
  return data.formSchemasMutation.addPermission
}

/**
 * Removes a permission grant from a form schema.
 */
export async function deleteFormSchemaPermission(
  apiUrl: string,
  entityId: string,
  groupId: string,
  action: string,
  token?: string,
): Promise<FormSchemaPermission> {
  type R = { formSchemasMutation: { deletePermission: FormSchemaPermission } }
  const data = await graphqlRequest<R>(
    apiUrl,
    DELETE_FORM_SCHEMA_PERMISSION,
    { permission: { entityId, groupId, action } },
    token,
  )
  return data.formSchemasMutation.deletePermission
}

export { GraphQLError, NetworkError }
