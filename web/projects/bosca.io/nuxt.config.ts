export default defineNuxtConfig({
  modules: [
    '@bosca/ui/nuxt',
    '@nuxt/eslint',
    '@nuxtjs/mdc'
  ],

  devtools: { enabled: true },

  app: {
    head: {
      htmlAttrs: { 'data-theme': 'dark' },
      link: [
        { rel: 'icon', href: '/favicon.ico' }
      ]
    }
  },

  // Fonts are self-hosted (assets/css/fonts.css + public/fonts/) so the
  // critical path has no external stylesheet request.
  css: ['~/assets/css/fonts.css', '~/assets/css/main.css'],

  runtimeConfig: {
    apiUrl: process.env.API_URL || 'http://localhost:8080',
    // Target of /cli/install.sh on bosca.io to install the CLI
    // (NUXT_CLI_INSTALL_SCRIPT_URL).
    cliInstallScriptUrl: 'https://raw.githubusercontent.com/bosca-io/bosca/main/cli/install.sh',
    public: {
      apiUrl: '',
      // Canonical origin for absolute URLs (canonical links, og:url/og:image,
      // robots.txt, sitemap.xml). No trailing slash.
      siteUrl: process.env.SITE_URL || 'https://bosca.io',
      authDomain: process.env.AUTH_DOMAIN || 'localhost',
      authCookiePrefix: '',
      // Same-origin base for the analytics SDK. The browser posts to
      // `/api/v1/events` + `/api/v1/installation`; in prod the bosca.io
      // HTTPRoute forwards those two prefixes to bosca-collector, and the
      // dev proxy below mirrors that to the local collector on :8081.
      analyticsUrl: process.env.ANALYTICS_URL || '/api/v1',
      appVersion: process.env.APP_VERSION || '0.0.0',
      // Deployment-provided legal documents (NUXT_PUBLIC_TERMS_URL /
      // NUXT_PUBLIC_PRIVACY_URL). Footers link only the ones that are set.
      termsUrl: '',
      privacyUrl: ''
    }
  },

  routeRules: {
    // The public marketing surface is static content — prerender it so
    // crawlers get instant, stable HTML.
    '/': { prerender: true },
    '/bml-reference/**': { prerender: true },
    '/recommendation-models/**': { prerender: true },
    '/discover/**': { prerender: true },
    '/developers': { redirect: '/developers/getting-started' },
    '/developers/**': { prerender: true },
    '/graphql': {
      proxy: (process.env.API_URL || 'http://localhost:8080') + '/graphql'
    },
    // Analytics collector runs on :8081 locally; only /events and
    // /installation belong to it — other /api/v1 paths are the server's.
    '/api/v1/events': {
      proxy: (process.env.API_URL || 'http://localhost:8081') + '/api/v1/events'
    },
    '/api/v1/installation': {
      proxy: (process.env.API_URL || 'http://localhost:8081') + '/api/v1/installation'
    },
    '/oauth2/**': {
      proxy: {
        to: (process.env.API_URL || 'http://localhost:8080') + '/oauth2/**',
        fetchOptions: { redirect: 'manual' }
      }
    }
  },

  compatibilityDate: '2024-07-11',

  nitro: {
    prerender: {
      routes: [
        '/bml-reference/getting-started',
        '/bml-reference/grammar',
        '/bml-reference/tag-reference',
        '/bml-reference/islands',
        '/bml-reference/examples',
        '/recommendation-models/start-here',
        '/recommendation-models/observations',
        '/recommendation-models/datasets',
        '/recommendation-models/content-model',
        '/recommendation-models/towers',
        '/recommendation-models/training',
        '/recommendation-models/export'
      ]
    }
  },

  vite: {
    ssr: {
      noExternal: ['@bosca/ui', '@bosca/auth-client-browser', '@bosca/analytics-client-browser', '@bosca/forms']
    }
  },

  eslint: {
    config: {
      stylistic: {
        commaDangle: 'never',
        braceStyle: '1tbs'
      }
    }
  }
})
