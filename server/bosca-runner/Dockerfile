# =============================================================================
# bosca-runner runtime image (JDK 26)
#
# Build context is server/ (as selected by scripts/release/build-image.sh).
# =============================================================================
FROM azul/zulu-openjdk:26.0.0-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY scripts/install-postgresql-client.sh /tmp/install-postgresql-client.sh
RUN sh /tmp/install-postgresql-client.sh && rm /tmp/install-postgresql-client.sh

ARG ARTIFACT_SHA
RUN mkdir /app
WORKDIR /app
#COPY bosca-runner/build/install/bosca-runner/ /app/
COPY artifacts/${ARTIFACT_SHA}/bosca-runner/ /app/
RUN chmod +x ./bin/bosca-runner

EXPOSE 8080
ENTRYPOINT ["./bin/bosca-runner"]
