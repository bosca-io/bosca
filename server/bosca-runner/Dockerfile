# =============================================================================
# bosca-runner runtime image (JDK 26)
#
# Build context is the repository root.
# =============================================================================
FROM azul/zulu-openjdk:26.0.0-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

ARG ARTIFACT_SHA
RUN mkdir /app
WORKDIR /app
#COPY bosca-runner/build/install/bosca-runner/ /app/
COPY artifacts/${ARTIFACT_SHA}/bosca-runner/ /app/
RUN chmod +x ./bin/bosca-runner

EXPOSE 8080
ENTRYPOINT ["./bin/bosca-runner"]
