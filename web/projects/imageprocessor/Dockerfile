# =============================================================================
# imageprocessor runtime image
#
# Packages the pre-built deploy output.
# Build context is the deploy output directory.
# =============================================================================

# glibc base: the deploy output carries sharp binaries built on a glibc Linux host.
FROM node:22-bookworm-slim
WORKDIR /app

COPY . ./

ENV NODE_ENV=production
ENV PORT=8003
ENV HOST=0.0.0.0

EXPOSE 8003

CMD ["node", "/app/lib/main.js"]
