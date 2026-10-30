# Build context: server/. The release script stages the Gradle native executable.
FROM debian:bookworm-slim
ARG ARTIFACT_SHA
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/atlas-server-native/bosca-atlas-server /app/bosca-atlas-server
RUN chmod +x /app/bosca-atlas-server
EXPOSE 8094 8095
ENTRYPOINT ["./bosca-atlas-server"]
