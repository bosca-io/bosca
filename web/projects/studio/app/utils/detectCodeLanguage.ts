/**
 * Detects a canonical language id from a file path.
 *
 * The returned id is consumed by `CodeEditor.loadLanguageExtension`, which is
 * the single place that maps these ids to CodeMirror language extensions.
 * Keeping detection separate from extension loading lets the function be
 * unit-tested without pulling in CodeMirror.
 *
 * Unknown files return `'text'`.
 */

const EXTENSION_MAP: Record<string, string> = {
  // JS / TS family
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
  ts: 'typescript', mts: 'typescript', cts: 'typescript', tsx: 'typescript',

  // Web
  html: 'html', htm: 'html', xhtml: 'html',
  vue: 'vue',
  svelte: 'html',
  css: 'css',
  scss: 'sass', sass: 'sass',
  less: 'less',
  styl: 'stylus', stylus: 'stylus',

  // Data / config
  json: 'json', jsonc: 'json',
  json5: 'json',
  yaml: 'yaml', yml: 'yaml',
  xml: 'xml', xsd: 'xml', xsl: 'xml', xslt: 'xml', svg: 'xml', plist: 'xml',
  toml: 'toml',
  ini: 'properties', cfg: 'properties', conf: 'properties', properties: 'properties',
  env: 'shell',

  // Markdown / docs
  md: 'markdown', markdown: 'markdown', mdx: 'markdown',
  rst: 'rst',
  tex: 'stex', latex: 'stex',

  // Systems
  c: 'cpp', h: 'cpp', cc: 'cpp', cpp: 'cpp', cxx: 'cpp', hh: 'cpp', hpp: 'cpp', hxx: 'cpp',
  rs: 'rust',
  go: 'go',
  java: 'java',
  kt: 'kotlin', kts: 'kotlin',
  scala: 'scala', sc: 'scala',
  swift: 'swift',
  m: 'cpp', mm: 'cpp', // Objective-C — close enough via cpp mode

  // Scripting
  py: 'python', pyw: 'python', pyi: 'python',
  rb: 'ruby', rake: 'ruby', gemspec: 'ruby',
  php: 'php', phtml: 'php',
  pl: 'perl', pm: 'perl',
  lua: 'lua',
  r: 'r',
  dart: 'dart',
  groovy: 'groovy', gradle: 'groovy',

  // Functional
  hs: 'haskell', lhs: 'haskell',
  ex: 'elixir', exs: 'elixir',
  erl: 'erlang', hrl: 'erlang',
  clj: 'clojure', cljs: 'clojure', cljc: 'clojure',
  lisp: 'commonlisp', lsp: 'commonlisp',
  ml: 'mllike', mli: 'mllike',
  fs: 'mllike', fsi: 'mllike', fsx: 'mllike',

  // Shell
  sh: 'shell', bash: 'shell', zsh: 'shell', fish: 'shell', ksh: 'shell',
  ps1: 'powershell', psm1: 'powershell',
  bat: 'shell', cmd: 'shell',

  // SQL
  sql: 'sql', psql: 'sql', mysql: 'sql',

  // Misc
  diff: 'diff', patch: 'diff',
  graphql: 'graphql', gql: 'graphql', graphqls: 'graphql',
  proto: 'protobuf',
  dockerfile: 'dockerfile',
  cmake: 'cmake',
  nginx: 'nginx',
  vim: 'vim',
  asm: 'gas', s: 'gas',
  pug: 'pug',
  ejs: 'html', hbs: 'html', mustache: 'html',
  log: 'text', txt: 'text',
}

const BASENAME_MAP: Record<string, string> = {
  'dockerfile': 'dockerfile',
  'containerfile': 'dockerfile',
  'makefile': 'cmake',
  'gnumakefile': 'cmake',
  'cmakelists.txt': 'cmake',
  'rakefile': 'ruby',
  'gemfile': 'ruby',
  'gemfile.lock': 'ruby',
  'guardfile': 'ruby',
  'vagrantfile': 'ruby',
  'procfile': 'yaml',
  'jenkinsfile': 'groovy',
  '.gitignore': 'properties',
  '.gitattributes': 'properties',
  '.editorconfig': 'properties',
  '.env': 'shell',
  '.bashrc': 'shell',
  '.zshrc': 'shell',
  '.bash_profile': 'shell',
  '.profile': 'shell',
  '.npmrc': 'properties',
  '.eslintrc': 'json',
  '.prettierrc': 'json',
  '.babelrc': 'json',
  'package.json': 'json',
  'tsconfig.json': 'json',
  'composer.json': 'json',
  'cargo.toml': 'toml',
  'pyproject.toml': 'toml',
  'go.mod': 'go',
  'go.sum': 'text',
  'license': 'text',
  'readme': 'markdown',
}

export function detectCodeLanguage(path: string): string {
  if (!path) return 'text'

  const base = path.split('/').pop() ?? path
  const lowerBase = base.toLowerCase()

  if (BASENAME_MAP[lowerBase]) return BASENAME_MAP[lowerBase]

  // Compound extensions like ".d.ts" — strip the leading qualifier before
  // looking up so they resolve to their final extension.
  if (lowerBase.endsWith('.d.ts')) return 'typescript'

  const dot = lowerBase.lastIndexOf('.')
  if (dot === -1 || dot === lowerBase.length - 1) {
    // No extension — fall back to a few well-known dotfile bases handled above,
    // otherwise assume plain text.
    return 'text'
  }

  const ext = lowerBase.slice(dot + 1)
  return EXTENSION_MAP[ext] ?? 'text'
}
