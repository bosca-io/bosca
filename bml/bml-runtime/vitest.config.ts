import { defineConfig } from "vitest/config"

export default defineConfig({
  test: {
    clearMocks: true,
    environment: "happy-dom",
    coverage: {
      provider: "v8",
      include: ["src/**"],
      reporter: ["text", "html"],
    },
  },
})
