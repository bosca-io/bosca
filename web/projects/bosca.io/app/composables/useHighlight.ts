import { createHighlighter, type Highlighter } from 'shiki'

let highlighterPromise: Promise<Highlighter> | null = null

const LOADED_LANGS = ['kotlin', 'graphql', 'sql', 'json', 'typescript', 'javascript', 'bash', 'yaml', 'html'] as const

// Languages without their own grammar render with the closest loaded one.
const LANG_ALIASES: Record<string, string> = {
  bml: 'html',
  ts: 'typescript',
  js: 'javascript'
}

function getHighlighter() {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: ['vitesse-dark', 'vitesse-light'],
      langs: [...LOADED_LANGS]
    })
  }
  return highlighterPromise
}

function escapeHtml(code: string): string {
  return code.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

export function useHighlight() {
  const cache = new Map<string, string>()

  async function highlight(code: string, lang: string): Promise<string> {
    const key = `${lang}:${code}`
    if (cache.has(key)) return cache.get(key)!
    const highlighter = await getHighlighter()
    const resolved = LANG_ALIASES[lang] ?? lang
    let html: string
    if (highlighter.getLoadedLanguages().includes(resolved)) {
      html = highlighter.codeToHtml(code.trim(), {
        lang: resolved,
        themes: {
          dark: 'vitesse-dark',
          light: 'vitesse-light'
        }
      })
    } else {
      // Unknown language — render as plain text rather than failing the block.
      html = `<pre class="shiki"><code>${escapeHtml(code.trim())}</code></pre>`
    }
    cache.set(key, html)
    return html
  }

  return { highlight }
}
