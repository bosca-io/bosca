# =============================================================================
# bosca-git-server native image (GraalVM 25)
#
# The native binary is pre-built by CI using Gradle nativeCompile.
#
# Build context is the repository root.
# =============================================================================
FROM debian:bookworm-slim

ARG ARTIFACT_SHA
RUN mkdir /app
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/git-server-native/bosca-git-server /app/bosca-git-server
RUN chmod +x ./bosca-git-server

EXPOSE 3000
ENTRYPOINT ["./bosca-git-server"]
