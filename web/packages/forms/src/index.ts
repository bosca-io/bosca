// Types
export type {
  FormSchema,
  FormSchemaInput,
  FormSchemaType,
  FormSchemaPermission,
  FormSchemaProfileMapping,
  FormSchemaProfileMappingAttribute,
  FormSchemaProfileMappingInput,
  FormSchemaProfileMappingAttributeInput,
  PermissionActionType,
  ProfileVisibility,
  ProfileInput,
  ProfileAttributeInput,
  FormSubmissionInput,
  SubmittedForm,
  JsonSchema,
  JsonSchemaProperty,
  UiSchema,
  UiSchemaNode,
  SectionNode,
  RowNode,
  TabsNode,
  TabNode,
  FieldNode,
  DisplayNode,
} from './types'

// GraphQL fetch utility for custom controls
export {
  graphqlFetch,
  type GraphqlFetchOptions,
  type GraphqlFetchResult,
} from './graphql-fetch'

// GraphQL API
export {
  getFormSchemaByKey,
  getFormSchemaById,
  getAllFormSchemas,
  saveFormSchema,
  deleteFormSchema,
  setFormSchemaPublished,
  submitForm,
  addFormSchemaPermission,
  deleteFormSchemaPermission,
  GraphQLError,
  NetworkError,
} from './graphql'

// Nuxt integration
export {
  setupBoscaForms,
  useBoscaForms,
  BoscaFormsKey,
  type BoscaFormsConfig,
  type BoscaFormsState,
  type AnalyticsInstance,
  type FormsProfile,
  type FormsProfileAttribute,
} from './nuxt'

// Validation
export {
  validateFormData,
  validateField,
  type ValidationErrors,
} from './validation'

// Form context (provide/inject for child controls)
export {
  provideBoscaFormContext,
  useBoscaFormContext,
  type BoscaFormContext,
  BoscaFormContextKey,
} from './context'

// Control registry
export {
  registerControl,
  getControl,
  getRegisteredControls,
} from './controls'

// Components
export { default as BoscaForm } from './components/BoscaForm.vue'
export { default as BoscaFormRenderer } from './components/BoscaFormRenderer.vue'
