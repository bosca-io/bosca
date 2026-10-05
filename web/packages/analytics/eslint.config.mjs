import typescriptEslint from '@typescript-eslint/eslint-plugin'
import stylisticTs from '@stylistic/eslint-plugin-ts'
import globals from 'globals'
import tsParser from '@typescript-eslint/parser'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import js from '@eslint/js'
import { FlatCompat } from '@eslint/eslintrc'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const compat = new FlatCompat({
  baseDirectory: __dirname,
  recommendedConfig: js.configs.recommended,
  allConfig: js.configs.all,
})

export default [{
  ignores: ['**/node_modules', '**/dist'],
}, ...compat.extends('eslint:recommended'), {
  plugins: {
    '@typescript-eslint': typescriptEslint,
    '@stylistic/ts': stylisticTs,
  },

  languageOptions: {
    globals: {
      ...globals.node,
    },

    parser: tsParser,
    ecmaVersion: 12,
    sourceType: 'module',
  },

  rules: {
    semi: ['error', 'never'],
    '@stylistic/ts/quotes': ['error', 'single'],
    '@stylistic/ts/object-curly-spacing': ['error', 'always'],

    '@stylistic/ts/space-before-function-paren': ['error', {
      anonymous: 'always',
      named: 'never',
      asyncArrow: 'always',
    }],

    '@stylistic/ts/comma-dangle': ['error', 'always-multiline'],
    '@stylistic/ts/indent': ['error', 2],

    '@stylistic/ts/keyword-spacing': ['error', {
      before: true,
      after: true,
    }],

    '@typescript-eslint/no-unused-vars': ['error', {
      argsIgnorePattern: '^_',
      varsIgnorePattern: '^_',
      caughtErrorsIgnorePattern: '^_',
    }],

    'no-unused-vars': 'off',
  },
}]