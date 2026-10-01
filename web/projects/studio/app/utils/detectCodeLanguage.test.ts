import { describe, it, expect } from 'vitest'
import { detectCodeLanguage } from './detectCodeLanguage'

describe('detectCodeLanguage', () => {
  describe('extensions', () => {
    it.each([
      ['index.ts', 'typescript'],
      ['App.tsx', 'typescript'],
      ['types.d.ts', 'typescript'],
      ['app.js', 'javascript'],
      ['component.jsx', 'javascript'],
      ['main.mjs', 'javascript'],
      ['module.cjs', 'javascript'],
      ['Component.vue', 'vue'],
      ['index.html', 'html'],
      ['styles.css', 'css'],
      ['styles.scss', 'sass'],
      ['styles.less', 'less'],
      ['config.json', 'json'],
      ['tsconfig.jsonc', 'json'],
      ['data.yaml', 'yaml'],
      ['ci.yml', 'yaml'],
      ['feed.xml', 'xml'],
      ['icon.svg', 'xml'],
      ['Cargo.toml', 'toml'], // BASENAME wins before this, but ensures lookup is case-insensitive
      ['main.py', 'python'],
      ['lib.rs', 'rust'],
      ['main.go', 'go'],
      ['App.java', 'java'],
      ['main.kt', 'kotlin'],
      ['main.swift', 'swift'],
      ['main.c', 'cpp'],
      ['main.cpp', 'cpp'],
      ['header.h', 'cpp'],
      ['script.rb', 'ruby'],
      ['index.php', 'php'],
      ['script.lua', 'lua'],
      ['script.sh', 'shell'],
      ['deploy.bash', 'shell'],
      ['query.sql', 'sql'],
      ['README.md', 'markdown'],
      ['changes.diff', 'diff'],
      ['schema.graphql', 'graphql'],
      ['messages.proto', 'protobuf'],
    ])('detects %s as %s', (path, expected) => {
      expect(detectCodeLanguage(path)).toBe(expected)
    })
  })

  describe('basenames', () => {
    it.each([
      ['Dockerfile', 'dockerfile'],
      ['some/path/Dockerfile', 'dockerfile'],
      ['Containerfile', 'dockerfile'],
      ['Makefile', 'cmake'],
      ['CMakeLists.txt', 'cmake'],
      ['Rakefile', 'ruby'],
      ['Gemfile', 'ruby'],
      ['package.json', 'json'],
      ['tsconfig.json', 'json'],
      ['Cargo.toml', 'toml'],
      ['pyproject.toml', 'toml'],
      ['go.mod', 'go'],
      ['Jenkinsfile', 'groovy'],
      ['.gitignore', 'properties'],
      ['.eslintrc', 'json'],
      ['.env', 'shell'],
    ])('detects basename %s as %s', (path, expected) => {
      expect(detectCodeLanguage(path)).toBe(expected)
    })
  })

  describe('paths with directories', () => {
    it('strips directories before detecting', () => {
      expect(detectCodeLanguage('src/components/App.tsx')).toBe('typescript')
      expect(detectCodeLanguage('a/b/c/d/main.go')).toBe('go')
    })
  })

  describe('unknown / falsy input', () => {
    it('returns text for empty input', () => {
      expect(detectCodeLanguage('')).toBe('text')
    })

    it('returns text for files with no extension or known basename', () => {
      expect(detectCodeLanguage('UNRECOGNIZED')).toBe('text')
      expect(detectCodeLanguage('weird.unknownext')).toBe('text')
    })

    it('returns text for trailing dot', () => {
      expect(detectCodeLanguage('foo.')).toBe('text')
    })
  })

  describe('case insensitivity', () => {
    it('treats extensions case-insensitively', () => {
      expect(detectCodeLanguage('SCRIPT.PY')).toBe('python')
      expect(detectCodeLanguage('Index.HTML')).toBe('html')
    })

    it('treats known basenames case-insensitively', () => {
      expect(detectCodeLanguage('dockerfile')).toBe('dockerfile')
      expect(detectCodeLanguage('MAKEFILE')).toBe('cmake')
    })
  })
})
