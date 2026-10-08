# =============================================================================
# Bosca CLI / CI agent runtime image
#
# The Linux native binary must be built and staged before this image is built.
# Build context is cli/.
# =============================================================================
ARG NODE_MAJOR=22

FROM docker:28.5.2-cli AS docker-cli

FROM node:${NODE_MAJOR}-bookworm-slim

ARG ARTIFACT_SHA
ARG NVM_VERSION=0.40.1
ARG GRAALVM_STREAM=25i4
ARG GRAALVM_JDK_VERSION=25.0.4.1.1
ARG GRAALVM_ARCH=x64
ARG ANDROID_COMMAND_LINE_TOOLS_VERSION=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# These are the tools the Bosca agent itself invokes for checkout, shell,
# artifact, cache, notification, and container-backed pipeline steps. The
# Docker daemon is intentionally not started in this image; Kubernetes agents
# can connect the client to a DinD sidecar or another daemon.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        build-essential \
        ca-certificates \
        cmake \
        curl \
        file \
        git \
        gnupg \
        gzip \
        jq \
        libgl1 \
        libx11-6 \
        ninja-build \
        openssh-client \
        pkg-config \
        python3 \
        python3-pip \
        python3-venv \
        rsync \
        sudo \
        tar \
        tini \
        unzip \
        zip \
        zlib1g-dev \
    && install -d -m 0755 /etc/apt/keyrings \
    && curl -fsSL https://repos.azul.com/azul-repo.key \
        | gpg --dearmor --batch -o /etc/apt/keyrings/azul.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
        > /etc/apt/sources.list.d/zulu.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        zulu17-ca-jdk-headless \
        zulu25-ca-jdk-headless \
    && rm -rf /var/lib/apt/lists/* \
    && install -d /opt/jdks \
    && ln -s /usr/lib/jvm/zulu17-ca-amd64 /opt/jdks/zulu-17 \
    && ln -s /usr/lib/jvm/zulu25-ca-amd64 /opt/jdks/zulu-25 \
    && install -d /opt/graalvm \
    && curl -fsSL \
        "https://gds.oracle.com/download/graal/${GRAALVM_STREAM}/archive/graalvm-jdk-${GRAALVM_STREAM}-${GRAALVM_JDK_VERSION}_linux-${GRAALVM_ARCH}_bin.tar.gz" \
        | tar -xz --strip-components=1 -C /opt/graalvm \
    && test -x /opt/graalvm/bin/native-image \
    && install -d /opt/android-sdk/cmdline-tools /tmp/android-command-line-tools \
    && curl -fsSL \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip" \
        -o /tmp/android-command-line-tools.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/android-command-line-tools.zip" \
        | sha256sum -c - \
    && unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-command-line-tools \
    && mv /tmp/android-command-line-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm -rf /tmp/android-command-line-tools /tmp/android-command-line-tools.zip \
    && (set +o pipefail; yes \
        | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
            --sdk_root=/opt/android-sdk --licenses >/dev/null) \
    && /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
        --sdk_root=/opt/android-sdk \
        "build-tools;36.0.0" \
        "build-tools;37.0.0" \
        "platform-tools" \
        "platforms;android-36" \
        "platforms;android-37.0" \
    && groupadd --gid 10002 docker \
    && groupadd --gid 10001 bosca \
    && useradd --create-home --uid 10001 --gid 10001 --shell /bin/bash bosca \
    && usermod --append --groups docker bosca \
    && echo "bosca ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/bosca \
    && chmod 0440 /etc/sudoers.d/bosca \
    && install -d -o bosca -g bosca \
        /home/bosca/.bosca \
        /home/bosca/.config \
        /home/bosca/.config/bosca \
        /tmp/bosca-ci \
        /workspace \
    && install -d -o bosca -g bosca /home/bosca/.bosca/graalvm \
    && ln -s /opt/graalvm \
        "/home/bosca/.bosca/graalvm/${GRAALVM_STREAM}-${GRAALVM_JDK_VERSION}-linux-${GRAALVM_ARCH}" \
    && chown -h bosca:bosca \
        "/home/bosca/.bosca/graalvm/${GRAALVM_STREAM}-${GRAALVM_JDK_VERSION}-linux-${GRAALVM_ARCH}" \
    && chown -R bosca:bosca /opt/android-sdk

ENV HOME=/home/bosca \
    XDG_CONFIG_HOME=/home/bosca/.config \
    NVM_DIR=/home/bosca/.config/nvm \
    JAVA_HOME=/opt/jdks/zulu-25 \
    GRAALVM_HOME=/opt/graalvm \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH=/opt/jdks/zulu-25/bin:/opt/graalvm/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

# Create the package-manager shims while the image still has permission to
# write /usr/local/bin. The non-root setup-pnpm action can then select a pnpm
# version in the Bosca user's Corepack cache.
RUN corepack enable

USER bosca

# setup-node uses NVM for version selection. Preinstall both NVM and the Node
# release supplied by the base image so the common Node 22 path does not
# bootstrap either tool during a job.
RUN export PROFILE=/dev/null \
    && curl -fsSL "https://raw.githubusercontent.com/nvm-sh/nvm/v${NVM_VERSION}/install.sh" | NODE_VERSION= bash \
    && . "${NVM_DIR}/nvm.sh" \
    && test "$(nvm --version)" = "${NVM_VERSION}" \
    && nvm install "${NODE_VERSION}" \
    && nvm alias default "${NODE_VERSION}" \
    && nvm use default \
    && test "$(node --version)" = "$(nvm version "${NODE_VERSION}")"

USER root

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/ /usr/local/libexec/docker/cli-plugins/
COPY artifacts/${ARTIFACT_SHA}/bosca-cli-native/bosca /usr/local/bin/bosca
RUN chmod 0755 /usr/local/bin/bosca

USER bosca
WORKDIR /workspace

ENTRYPOINT ["/usr/bin/tini", "--", "bosca"]
CMD ["ci", "agent", "start"]
