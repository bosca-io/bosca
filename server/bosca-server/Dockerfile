# =============================================================================
# bosca-server runtime image (JDK 26)
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
COPY artifacts/${ARTIFACT_SHA}/bosca-server/ /app/
RUN chmod +x ./bin/bosca-server

EXPOSE 8080
ENTRYPOINT ["./bin/bosca-server"]
