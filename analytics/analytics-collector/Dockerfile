# syntax=docker/dockerfile:1
# =============================================================================
# analytics-collector runtime image
#
# Build context is the repository root.
# =============================================================================
FROM azul/zulu-openjdk:26.0.0-jdk

ARG ARTIFACT_SHA
RUN mkdir /app
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/analytics-collector/ /app/
RUN chmod +x ./bin/analytics-collector

EXPOSE 8081
ENTRYPOINT ["./bin/analytics-collector"]
