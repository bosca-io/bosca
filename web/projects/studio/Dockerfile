# =============================================================================
# studio runtime image
#
# Packages the pre-built Nuxt .output directory.
# Build context is the repository root.
# =============================================================================

FROM node:22-alpine
WORKDIR /app

COPY projects/studio/.output/ ./

ENV PORT=3000
ENV HOST=0.0.0.0

EXPOSE 3000

CMD ["node", "/app/server/index.mjs"]
