# Copy the CLI from the release script's local stage or a qualified image pinned by digest.
# No executables are downloaded at session start.
ARG BOSCA_CLI_IMAGE
FROM ${BOSCA_CLI_IMAGE} AS cli
FROM node:22-trixie-slim AS base
ARG BOSCA_KIT_VERSIONS
LABEL io.bosca.kit.versions="${BOSCA_KIT_VERSIONS}"
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates git tini \
    && rm -rf /var/lib/apt/lists/*
COPY --from=cli /usr/local/bin/bosca /usr/local/bin/bosca
RUN mkdir -p /workspace /state/harness /state/scratch /state/control /state/home \
    && chown -R node:node /workspace /state
ENV HOME=/state/home TMPDIR=/state/scratch NO_BROWSER=1 BOSCA_NO_UPDATE_CHECK=1
WORKDIR /workspace
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "-s", "--", "/usr/local/bin/bosca", "harness", "serve"]

FROM base AS claude
WORKDIR /opt/runtime
COPY claude/package.json claude/package-lock.json claude/compatibility.json ./
RUN npm ci --omit=dev --no-audit --no-fund
ENV CLAUDE_CONFIG_DIR=/state/harness CLAUDE_CODE_TMPDIR=/state/scratch \
    BOSCA_ACP_COMMAND='["/opt/runtime/node_modules/.bin/claude-agent-acp"]'
USER node
WORKDIR /workspace

# Keep Codex as the default target.
FROM base AS codex
WORKDIR /opt/runtime
COPY package.json package-lock.json compatibility.json ./
RUN npm ci --omit=dev --no-audit --no-fund
ENV CODEX_HOME=/state/harness INITIAL_AGENT_MODE=workspace-write \
    BOSCA_ACP_COMMAND='["/opt/runtime/node_modules/.bin/codex-acp"]'
USER node
WORKDIR /workspace
