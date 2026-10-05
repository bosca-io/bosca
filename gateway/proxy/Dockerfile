# syntax=docker/dockerfile:1.7
# Multi-stage build for the Bosca HTTP Auth Gateway.
#
# Builder: clux/muslrust ships a real musl cross-toolchain plus a musl-built
# OpenSSL, so crates with C/asm components (ring, openssl-sys, ...) link
# cleanly into a fully static binary. The image is multi-arch (amd64/arm64);
# Docker pulls the variant matching $TARGETPLATFORM, so the same Dockerfile
# builds an arm64 binary on Apple Silicon and an amd64 binary in CI — or both
# at once under `docker buildx build --platform=linux/amd64,linux/arm64`.
#
# Runtime: distroless/static contains no libc and no shared libraries, so the
# binary must be 100% statically linked — which is why we target musl.

# ---------- Stage 1: build ----------
FROM clux/muslrust:stable AS builder

ARG TARGETARCH

WORKDIR /src

# Translate Docker's TARGETARCH (amd64/arm64) to the matching Rust musl
# target triple. Persist it so the cache-mounted build step can read it back.
RUN set -eux; \
    case "$TARGETARCH" in \
        amd64) TARGET=x86_64-unknown-linux-musl ;; \
        arm64) TARGET=aarch64-unknown-linux-musl ;; \
        *)     echo "Unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    rustup target add "$TARGET"; \
    echo "$TARGET" > /target_triple

COPY Cargo.toml Cargo.lock ./
COPY src ./src

# Cache mounts keep ~/.cargo (downloaded crate sources & git deps) and the
# target/ directory warm across builds. They live outside the image layers,
# so the compiled binary is copied to a stable path before the next stage.
RUN --mount=type=cache,target=/root/.cargo/registry,sharing=locked \
    --mount=type=cache,target=/root/.cargo/git,sharing=locked \
    --mount=type=cache,target=/src/target,sharing=locked \
    set -eux; \
    TARGET=$(cat /target_triple); \
    cargo build --release --locked --target "$TARGET" --bin bosca-gateway; \
    cp "target/$TARGET/release/bosca-gateway" /bosca-gateway

# ---------- Stage 2: runtime ----------
FROM gcr.io/distroless/static-debian12

USER 65534:65534

WORKDIR /app

COPY --from=builder /bosca-gateway /usr/local/bin/bosca-gateway

VOLUME ["/var/lib/gateway"]

EXPOSE 8443

ENV GATEWAY_CONFIG=/etc/gateway/config.toml \
    RUST_LOG=info

ENTRYPOINT ["/usr/local/bin/bosca-gateway"]
