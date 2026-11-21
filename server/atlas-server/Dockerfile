# Build context: server/. Stage the unpacked Gradle nativeDistZip output.
FROM debian:bookworm-slim
ARG ARTIFACT_SHA
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/atlas-server-native/ /app/
RUN chmod +x /app/bosca-atlas-server
EXPOSE 8094 8095
ENTRYPOINT ["./bosca-atlas-server"]
