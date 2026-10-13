export default defineNuxtConfig({
  compatibilityDate: '2025-05-01',
  devtools: { enabled: false },
  modules: ['@nuxt/eslint', '@nuxtjs/mdc', '@bosca/ui/nuxt', '@bosca/ui-analytics/nuxt', '@bosca/forms/module'],

  runtimeConfig: {
    apiUrl: process.env.API_URL || 'http://localhost:8080',
    public: {
      apiUrl: '',
      wsUrl: process.env.WS_URL || 'ws://localhost:8080',
      authDomain: process.env.AUTH_DOMAIN || 'localhost',
      // Exact custom auth cookie prefix, overridden by NUXT_PUBLIC_AUTH_COOKIE_PREFIX.
      // Empty selects the always-available `_bat` cookie.
      authCookiePrefix: '',
      analyticsUrl: process.env.ANALYTICS_URL || '/api/v1',
      analyticsAnonymous: process.env.ANALYTICS_ANONYMOUS === 'true',
      analyticsOmitCredentials: process.env.ANALYTICS_OMIT_CREDENTIALS === 'true',
      appVersion: process.env.APP_VERSION || '0.0.0',
      imageBaseUrl: '/content/image',
      gitBaseUrl: process.env.GIT_BASE_URL || '/git',
      gitServerUrl: process.env.GIT_SERVER_URL || 'http://localhost:8080',
      artifactsUrl: process.env.ARTIFACTS_URL || 'http://localhost:8084',
      // External documentation site. Overridable per-deployment; defaults to the
      // Studio section of the public docs served by the bosca.io site.
      docsUrl: process.env.DOCS_URL || 'https://bosca.io',
      // Deployment-provided legal documents (NUXT_PUBLIC_TERMS_URL /
      // NUXT_PUBLIC_PRIVACY_URL). When either is set, sign-up links to it and
      // requires agreement; when both are empty, sign-up shows no consent step.
      termsUrl: '',
      privacyUrl: '',
    },
  },

  routeRules: {
    '/**': {
      headers: { 'X-Robots-Tag': 'noindex, nofollow' },
    },
    '/api/**': { cors: true },
    '/content/**': {
      proxy: (process.env.API_URL || 'http://localhost:8080') + '/content/**',
    },
    '/oauth2/**': {
      proxy: {
        to: (process.env.API_URL || 'http://localhost:8080') + '/oauth2/**',
        fetchOptions: { redirect: 'manual' },
      },
    },
    '/api/v1/events': {
      proxy: (process.env.API_URL || 'http://localhost:8081') + '/api/v1/events',
    },
    '/api/v1/security/exchange-token': {
      proxy: {
        to: (process.env.API_URL || 'http://localhost:8080') + '/api/v1/security/exchange-token',
        fetchOptions: { redirect: 'manual' },
      },
    },
    '/api/v1/**': {
      proxy: (process.env.API_URL || 'http://localhost:8080') + '/api/v1/**',
    },
    '/ci/**': {
      proxy: (process.env.API_URL || 'http://localhost:8080') + '/ci/**',
    },
    '/graphql': {
      proxy: (process.env.API_URL || 'http://localhost:8080') + '/graphql',
    },
  },

  nitro: {
    experimental: {
      websocket: true,
    },
    devProxy: {
      '/graphqlws': {
        target: 'http://localhost:8080/graphqlws',
        ws: true,
        changeOrigin: true,
      },
      '/collaboration': {
        target: 'ws://localhost:8080/collaboration',
        ws: true,
        changeOrigin: true,
      },
    },
  },

  vite: {
    ssr: {
      noExternal: [
        '@bosca/ui',
        '@bosca/ui-analytics',
        '@bosca/auth-client-browser',
        '@bosca/forms',
        '@bosca/analytics-client-browser',
        'vue-chrts',
        'json-editor-vue',
        'vanilla-jsoneditor',
      ],
    },
  },

  app: {
    head: {
      title: 'Bosca | Studio',
      htmlAttrs: { lang: 'en', 'data-theme': 'dark' },
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        { name: 'robots', content: 'noindex, nofollow' },
      ],
      link: [
        { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' },
        {
          rel: 'stylesheet',
          href: 'https://fonts.googleapis.com/css2?family=Geist:wght@300;400;500;600;700&family=Geist+Mono:wght@400;500;600&display=swap',
        },
      ],
    },
  },

  components: [
    { path: '~/components/hud', pathPrefix: true, prefix: 'Hud' },
    { path: '~/components/form-builder', pathPrefix: true, prefix: 'FormBuilder' },
    { path: '~/components/templates', pathPrefix: true, prefix: 'Templates' },
    { path: '~/components', pathPrefix: false },
  ],

  css: ['~/assets/css/main.css', '~/assets/css/editor.css', '~/assets/css/plyr-overrides.css'],
})
