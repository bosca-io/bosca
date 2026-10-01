import gql from 'graphql-tag'

declare module '#app' {
  interface NuxtApp {
    _languageManager?: LanguageManager
  }
}

const languagesQuery = gql`
  query GetLanguages {
    languages {
      all {
        tag
        name
        localName
      }
    }
  }
`

export interface Language {
  tag: string
  name: string
}

export class LanguageManager {
  private readonly _cookie
  private readonly _current

  constructor(private readonly _languages: Ref<Language[]>) {
    this._cookie = useCookie('_language', {
      path: '/',
      httpOnly: false,
    })
    this._current = ref<Language>({
      tag: this._cookie.value || 'en',
      name: this._cookie.value ? '--' : 'English',
    })
    const queryLanguageTag = useRoute().query.languageTag
    if (_languages.value && _languages.value.length > 0) {
      if (queryLanguageTag) {
        this.setLanguageTag(queryLanguageTag.toString())
      } else if (this._cookie.value) {
        this.setLanguageTag(this._cookie.value)
      }
    }
    watch(this._current, (value) => {
      this._cookie.value = value.tag
    })
    watch(_languages, (languages, oldLanguages) => {
      if (oldLanguages.length === 0 && languages.length > 0) {
        if (queryLanguageTag) {
          this.setLanguageTag(queryLanguageTag.toString())
        } else if (this._cookie.value) {
          this.setLanguageTag(this._cookie.value)
        }
      }
    })
  }

  get current() {
    return this._current
  }

  getLanguage(tag: string): Language | undefined {
    return this._languages.value?.find(l => l.tag.toLowerCase() === tag.toLowerCase())
  }

  setLanguageTag(tag: string) {
    const language = this.getLanguage(tag)
    this._cookie.value = tag
    this._current.value = language || { tag: 'en', name: 'English' }
  }

  get languages(): Language[] {
    return this._languages.value || []
  }

  get languagesRef(): Ref<Language[]> {
    return this._languages
  }
}

/**
 * Shared language composable providing a single reactive language state
 * across all pages. Persists the selected language to a cookie and
 * supports URL query param initialization (?languageTag=...).
 */
export function useLanguage(): LanguageManager {
  const nuxtApp = useNuxtApp()

  if (nuxtApp._languageManager) {
    return nuxtApp._languageManager as LanguageManager
  }

  const languages = ref<Language[]>([])

  if (import.meta.client) {
    const { query } = useGraphQL()
    query<{
      languages: { all: Array<{ tag: string; name: string; localName: string | null }> }
    }>(languagesQuery).then((result) => {
      languages.value = result.languages.all.map(l => ({
        tag: l.tag,
        name: l.localName || l.name,
      }))
    }).catch((e) => {
      console.error('Failed to load languages', e)
    })
  }

  const manager = new LanguageManager(languages)
  nuxtApp._languageManager = manager
  return manager
}
