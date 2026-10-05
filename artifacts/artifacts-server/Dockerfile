# syntax=docker/dockerfile:1
# =============================================================================
# artifacts-server runtime image
#
# Build context is the repository root.
# =============================================================================
FROM azul/zulu-openjdk:26.0.0-jdk

ARG ARTIFACT_SHA
RUN mkdir /app
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/artifacts-server/ /app/
RUN chmod +x ./bin/artifacts-server

EXPOSE 8084
ENTRYPOINT ["./bin/artifacts-server"]
