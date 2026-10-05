const schema = process.env.STUDIO_GRAPHQL_SCHEMA ?? 'http://localhost:8080/graphql'

export default {
  overwrite: true,
  schema,
  generates: {
    'schema.graphqls': {
      plugins: ['schema-ast'],
      config: {
        includeDirectives: true,
      },
    },
    'app/types/graphql.ts': {
      plugins: ['typescript'],
      config: {
        addUnderscoreToArgsType: true,
        scalars: {
          UUID: 'string',
          JSON: 'any',
          DateTime: 'string',
          Long: 'number',
          Upload: 'File',
        },
      },
    },
  },
}
