export function useTheme() {
  const isDark = useState('theme-dark', () => true)

  function toggle() {
    isDark.value = !isDark.value
    if (import.meta.client) {
      document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
      localStorage.setItem('bosca-docs-theme', isDark.value ? 'dark' : 'light')
    }
  }

  function init() {
    if (import.meta.client) {
      const stored = localStorage.getItem('bosca-docs-theme')
      if (stored === 'light') {
        isDark.value = false
        document.documentElement.setAttribute('data-theme', 'light')
      }
    }
  }

  return { isDark, toggle, init }
}
