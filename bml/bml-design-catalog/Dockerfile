# Build on Linux x86_64 with :bml:bml-design-catalog:nativeCompile. Context: bml/.
FROM debian:bookworm-slim

ARG ARTIFACT_SHA
WORKDIR /app
COPY artifacts/${ARTIFACT_SHA}/bml-design-catalog-native/bml-design-catalog ./bml-design-catalog
RUN chmod +x ./bml-design-catalog

ENV BML_DEV=false
USER 10001:10001

EXPOSE 9093
ENTRYPOINT ["./bml-design-catalog"]
