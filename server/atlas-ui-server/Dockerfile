# Build context: server/. The release script stages the Gradle native executable.
FROM debian:bookworm-slim
ARG ARTIFACT_SHA
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/atlas-ui-server-native/bosca-atlas-ui-server /app/bosca-atlas-ui-server
RUN chmod +x /app/bosca-atlas-ui-server
EXPOSE 8095
ENTRYPOINT ["./bosca-atlas-ui-server"]
