module.exports = {
  env: {
    es2021: true,
    node: true,
  },
  extends: [
    'eslint:recommended',
  ],
  ignorePatterns: ['node_modules', 'dist', 'bosca-ui'],
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 12,
    sourceType: 'module',
  },
  plugins: [
    '@typescript-eslint',
    '@stylistic/ts',
  ],
  rules: {
    'semi': ['error', 'never'],
    '@stylistic/ts/quotes': ['error', 'single'],
    '@stylistic/ts/object-curly-spacing': ['error', 'always'],
    '@stylistic/ts/space-before-function-paren': ['error', {
      anonymous: 'always',
      named: 'never',
      asyncArrow: 'always',
    }],
    '@stylistic/ts/comma-dangle': ['error', 'always-multiline'],
    '@stylistic/ts/indent': ['error', 2],
    '@stylistic/ts/keyword-spacing': ['error', { before: true, after: true }],
    '@typescript-eslint/no-unused-vars': ['error', {
      argsIgnorePattern: '^_',
      varsIgnorePattern: '^_',
      caughtErrorsIgnorePattern: '^_',
    }],
    'no-unused-vars': 'off',
  },
}