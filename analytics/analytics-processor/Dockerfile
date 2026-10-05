# syntax=docker/dockerfile:1
# =============================================================================
# analytics-processor runtime image
#
# Build context is the repository root.
# =============================================================================
FROM azul/zulu-openjdk:26.0.0-jdk

ARG ARTIFACT_SHA
RUN mkdir /app
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/analytics-processor/ /app/
RUN chmod +x ./bin/analytics-processor

EXPOSE 8080
ENTRYPOINT ["./bin/analytics-processor"]
