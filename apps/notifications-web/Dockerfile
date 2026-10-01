# =============================================================================
# notifications-web native image (GraalVM) — the BML server-side-rendered email
# preferences / unsubscribe site (bml-server, port 9094).
#
# The native binary is pre-built by CI using Gradle nativeCompile:
#
#   ./gradlew :notifications-web:nativeCompile :notifications-web:bmlBundleClient
#
# Build context is apps/notifications-web. Published by
# .bosca/pipelines/release-image.yaml (scripts/release/build-image.sh).
# =============================================================================
FROM debian:bookworm-slim

RUN mkdir /app
WORKDIR /app
COPY build/native/nativeCompile/notifications-web /app/notifications-web
# Production page bundles (@click live islands) — bmlBundleClient's merged
# `js-prod` output. BmlServer links `<route>.page.js` in production mode, and
# the -D runtime arg points it at this baked-in copy.
COPY build/generated/bml/js-prod/ /app/client/
RUN chmod +x ./notifications-web

EXPOSE 9094
ENTRYPOINT ["./notifications-web", "-Dbml.clientDir=/app/client"]
