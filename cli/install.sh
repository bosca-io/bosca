#!/bin/sh
# shellcheck shell=sh
#
# Bosca CLI installer.
#
#   curl -fsSL https://bosca.io/cli/install.sh | sh
#
# Installs the latest released `bosca` binary for your platform from the
# project's GitHub Releases, or from BOSCA_CLI_ARTIFACTS_URL when configured.
#
# How "latest" is resolved
# ------------------------
# CLI releases are tagged `cli-v<version>` in a repository whose releases are
# shared with other independently versioned components. The releases listing is
# ordered newest-first, so the first stable `cli-v<major>.<minor>.<patch>` tag is
# the most recent CLI release. Its assets are named
# `bosca-<version>-<platform>.<ext>`; the asset for this OS/arch is read from the
# release itself rather than guessed, and verified against the release's
# SHA256SUMS asset.
# Bosca raw repositories list versions newest-first. The first stable numeric
# version is selected and verified against its SHA256SUMS file.
#
# Environment overrides
# ---------------------
#   BOSCA_VERSION          Install this exact version instead of the latest.
#   BOSCA_INSTALL_DIR      Where to place the binary for tarball installs
#                          (default: /usr/local/bin). The macOS .pkg always
#                          installs to /usr/local/bin (baked into the package).
#   BOSCA_CLI_REPOSITORY   Read releases from this owner/name repository
#                          instead of bosca-io/bosca (for example a fork).
#   BOSCA_CLI_ARTIFACTS_URL  Bosca raw repository download URL, for example
#                          https://artifacts.example.com/raw/bosca/bosca-cli.
#                          Uses the newest stable version in its listing.
#   BOSCA_CLI_ARTIFACTS_TOKEN  Optional Bosca API token for private artifacts.
#   GITHUB_TOKEN           Optional token for GitHub API calls, only needed
#                          when anonymous requests are rate limited.
#
# This script is POSIX sh; it does not require bash, jq, or any other tooling
# beyond curl (or wget), tar, and — on macOS — the system `installer`.

set -eu

# ── Configuration ───────────────────────────────────────────────────────────
REPOSITORY="${BOSCA_CLI_REPOSITORY:-bosca-io/bosca}"
TAG_PREFIX="cli-v"
API_URL="https://api.github.com/repos/${REPOSITORY}"
DOWNLOAD_BASE="https://github.com/${REPOSITORY}/releases/download"
ARTIFACTS_BASE="${BOSCA_CLI_ARTIFACTS_URL:-}"
ARTIFACTS_BASE="${ARTIFACTS_BASE%/}"
ARTIFACTS_API_URL=""

# ── Output helpers ──────────────────────────────────────────────────────────
# Status goes to stderr so a future `... | sh` that wants to capture stdout is
# never polluted, and so messages interleave correctly with sub-command output.
info() { printf '%s\n' "$*" >&2; }
warn() { printf 'warning: %s\n' "$*" >&2; }
err()  { printf 'error: %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

if [ -n "$ARTIFACTS_BASE" ]; then
  case "$ARTIFACTS_BASE" in
    http://*/raw/*/*|https://*/raw/*/*) ;;
    *) die "BOSCA_CLI_ARTIFACTS_URL must be a raw repository download URL: https://HOST/raw/NAMESPACE/NAME" ;;
  esac
  ARTIFACTS_NAME="${ARTIFACTS_BASE##*/}"
  ARTIFACTS_NAMESPACE_BASE="${ARTIFACTS_BASE%/*}"
  ARTIFACTS_API_URL="${ARTIFACTS_NAMESPACE_BASE}/api/${ARTIFACTS_NAME}"
fi

# ── Prerequisites ───────────────────────────────────────────────────────────
# A single downloader abstraction so the rest of the script doesn't care whether
# curl or wget is present. `http_get URL [FILE]` writes the body to stdout or
# FILE and returns non-zero on any HTTP/transport error.
if command -v curl >/dev/null 2>&1; then
  DOWNLOADER="curl"
elif command -v wget >/dev/null 2>&1; then
  DOWNLOADER="wget"
else
  die "neither curl nor wget found; please install one and re-run"
fi

# GitHub serves releases anonymously; GITHUB_TOKEN only lifts the anonymous
# API rate limit, so it is sent to api.github.com and nowhere else.
#
# http_get URL [FILE]
http_get() {
  _url="$1"
  _file="${2:-}"
  _auth=""
  _accept="application/json"
  case "$_url" in
    https://api.github.com/*)
      _accept="application/vnd.github+json"
      [ -n "${GITHUB_TOKEN:-}" ] && _auth="Authorization: Bearer ${GITHUB_TOKEN}"
      ;;
  esac
  if [ -n "$ARTIFACTS_BASE" ] && [ -n "${BOSCA_CLI_ARTIFACTS_TOKEN:-}" ]; then
    case "$_url" in
      "$ARTIFACTS_API_URL"|"$ARTIFACTS_BASE"/*) _auth="Authorization: Bearer ${BOSCA_CLI_ARTIFACTS_TOKEN}" ;;
    esac
  fi
  if [ "$DOWNLOADER" = "curl" ]; then
    set -- -fsSL --retry 3 -H "Accept: $_accept"
    [ -z "$_auth" ] || set -- "$@" -H "$_auth"
    [ -z "$_file" ] || set -- "$@" -o "$_file"
    curl "$@" "$_url"
  else
    if [ -n "$_file" ]; then
      set -- -qO "$_file"
    else
      set -- -qO-
    fi
    [ -z "$_auth" ] || set -- "$@" --header="$_auth"
    wget "$@" --header="Accept: $_accept" "$_url"
  fi
}

# ── Detect platform ─────────────────────────────────────────────────────────
# Map uname output to the token embedded in published filenames:
#   bosca-<version>-<platform>.<ext>   e.g. bosca-5.8.4-macos-arm64.pkg
detect_platform() {
  _os="$(uname -s)"
  _arch="$(uname -m)"
  case "$_os" in
    Darwin) _os_token="macos" ;;
    Linux)  _os_token="linux" ;;
    *) die "unsupported operating system: $_os (only macOS and Linux are supported)" ;;
  esac
  case "$_arch" in
    arm64|aarch64) _arch_token="arm64" ;;
    x86_64|amd64)  _arch_token="x86_64" ;;
    *) die "unsupported architecture: $_arch" ;;
  esac
  printf '%s-%s' "$_os_token" "$_arch_token"
}

PLATFORM="$(detect_platform)"
info "Detected platform: ${PLATFORM}"

# ── Resolve the version ─────────────────────────────────────────────────────
if [ -n "${BOSCA_VERSION:-}" ]; then
  VERSION="${BOSCA_VERSION#"$TAG_PREFIX"}"
elif [ -n "$ARTIFACTS_BASE" ]; then
  info "Querying ${ARTIFACTS_API_URL}"
  LISTING="$(http_get "$ARTIFACTS_API_URL")" || die "failed to list CLI versions in ${ARTIFACTS_BASE}"
  # RawListVersions returns versions newest-first. Ignore aliases and prereleases.
  VERSION="$(printf '%s' "$LISTING" \
    | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | sed 's/.*:[[:space:]]*"//; s/"$//' \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' \
    | head -1 \
  || true)"
  [ -n "$VERSION" ] || die "could not find a stable CLI version in ${ARTIFACTS_BASE}"
else
  LIST_URL="${API_URL}/releases?per_page=100"
  info "Querying ${LIST_URL}"
  LISTING="$(http_get "$LIST_URL")" || die "failed to list releases of ${REPOSITORY} (set GITHUB_TOKEN if rate limited)"
  # Releases are listed newest-first. Stable CLI tags are cli-v<major>.<minor>.<patch>;
  # drafts are invisible anonymously and prerelease versions carry a suffix.
  VERSION="$(printf '%s' "$LISTING" \
    | grep -o '"tag_name": *"[^"]*"' \
    | sed 's/.*"tag_name": *"//; s/"$//' \
    | grep -E "^${TAG_PREFIX}[0-9]+\.[0-9]+\.[0-9]+\$" \
    | head -1 \
    | sed "s/^${TAG_PREFIX}//" \
  || true)"
fi
[ -n "$VERSION" ] || die "could not find a CLI release (${TAG_PREFIX}<version>) in ${REPOSITORY}"
TAG="${TAG_PREFIX}${VERSION}"
info "Installing version: ${VERSION}"

# ── Find the asset filename for this platform/version ───────────────────────
# Filenames embed both version and platform (bosca-<version>-<platform>.<ext>),
# so this prefix uniquely identifies the right asset regardless of extension
# (.pkg, .tar.gz, …).
if [ -n "$ARTIFACTS_BASE" ]; then
  # The CLI pipeline publishes these package names to the raw repository.
  case "$PLATFORM" in
    macos-*) FILENAME="bosca-${VERSION}-${PLATFORM}.pkg" ;;
    linux-*) FILENAME="bosca-${VERSION}-${PLATFORM}.tar.gz" ;;
  esac
  DOWNLOAD_BASE="$ARTIFACTS_BASE"
  DOWNLOAD_VERSION="$VERSION"
else
  RELEASE="$(http_get "${API_URL}/releases/tags/${TAG}")" || die "release ${TAG} was not found in ${REPOSITORY}"
  ASSETS="$(printf '%s' "$RELEASE" | grep -o '"name": *"bosca-[^"]*"' | sed 's/.*"name": *"//; s/"$//' | sort -u)"
  PREFIX="bosca-${VERSION}-${PLATFORM}"
  # Escape regex metacharacters so the prefix is matched literally.
  PREFIX_RE="$(printf '%s' "$PREFIX" | sed 's/[.[\*^$/]/\\&/g')"
  FILENAME="$(printf '%s\n' "$ASSETS" | grep "^${PREFIX_RE}\." | head -1 || true)"

  if [ -z "$FILENAME" ]; then
    err "no published package found for ${PREFIX} (version ${VERSION}, platform ${PLATFORM})."
    if [ -n "$ASSETS" ]; then
      info "Assets in ${TAG}:"
      printf '%s\n' "$ASSETS" | sed 's/^/  - /' >&2
    fi
    die "no installable asset for your platform"
  fi
  DOWNLOAD_VERSION="$TAG"
fi
info "Selected package: ${FILENAME}"

# ── Download ────────────────────────────────────────────────────────────────
TMPDIR_INSTALL="$(mktemp -d 2>/dev/null || mktemp -d -t bosca-install)"
# Clean up the scratch directory on any exit path.
trap 'rm -rf "$TMPDIR_INSTALL"' EXIT INT TERM
PKG_PATH="${TMPDIR_INSTALL}/${FILENAME}"
SUMS_PATH="${TMPDIR_INSTALL}/SHA256SUMS"
DOWNLOAD_URL="${DOWNLOAD_BASE}/${DOWNLOAD_VERSION}/${FILENAME}"

info "Downloading ${DOWNLOAD_URL}"
http_get "$DOWNLOAD_URL" "$PKG_PATH" || die "download failed"
[ -s "$PKG_PATH" ] || die "downloaded file is empty"
http_get "${DOWNLOAD_BASE}/${DOWNLOAD_VERSION}/SHA256SUMS" "$SUMS_PATH" || die "release ${VERSION} has no SHA256SUMS asset"

# ── Verify integrity ────────────────────────────────────────────────────────
verify_digest() {
  _expected="$(awk -v f="$FILENAME" '$2 == f || $2 == "*" f { print $1 }' "$SUMS_PATH" | head -1)"
  [ -n "$_expected" ] || die "SHA256SUMS in ${TAG} has no entry for ${FILENAME}"
  if command -v shasum >/dev/null 2>&1; then
    _actual="$(shasum -a 256 "$PKG_PATH" | awk '{print $1}')"
  elif command -v sha256sum >/dev/null 2>&1; then
    _actual="$(sha256sum "$PKG_PATH" | awk '{print $1}')"
  else
    warn "no sha256 tool found; skipping integrity check"
    return 0
  fi
  if [ "$_actual" != "$_expected" ]; then
    die "checksum mismatch for ${FILENAME} (expected ${_expected}, got ${_actual})"
  fi
  info "Checksum verified (sha256:${_actual})"
}
verify_digest

# ── Privilege helper ────────────────────────────────────────────────────────
# Run a command as root: directly if we already are, via sudo if available,
# otherwise fail with guidance. sudo reads its password from the controlling
# terminal (/dev/tty), so this works even though our stdin is the curl pipe.
as_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    info "Elevating with sudo for: $*"
    sudo "$@"
  else
    die "this step needs root and sudo is not available; re-run as root or set BOSCA_INSTALL_DIR to a writable location"
  fi
}

# ── Install ─────────────────────────────────────────────────────────────────
install_pkg() {
  # Signed, notarized, stapled macOS installer. `installer` requires root and
  # places `bosca` at /usr/local/bin (the install-location baked into the pkg).
  command -v installer >/dev/null 2>&1 || die "macOS 'installer' tool not found"
  info "Installing ${FILENAME} (this may prompt for your password)…"
  as_root installer -pkg "$PKG_PATH" -target /
  INSTALLED_PATH="/usr/local/bin/bosca"
}

install_tarball() {
  # Linux (and any non-pkg) builds ship a tarball containing the `bosca` binary.
  info "Extracting ${FILENAME}…"
  tar -xzf "$PKG_PATH" -C "$TMPDIR_INSTALL" || die "failed to extract ${FILENAME}"
  _bin="$(find "$TMPDIR_INSTALL" -type f -name bosca 2>/dev/null | head -1)"
  [ -n "$_bin" ] || die "no 'bosca' binary found inside ${FILENAME}"
  chmod +x "$_bin"

  _dir="${BOSCA_INSTALL_DIR:-/usr/local/bin}"
  # If the chosen dir isn't writable and we can't elevate, fall back to a
  # per-user location rather than failing outright.
  if [ ! -d "$_dir" ] || [ ! -w "$_dir" ]; then
    if [ "$(id -u)" -ne 0 ] && ! command -v sudo >/dev/null 2>&1 && [ -z "${BOSCA_INSTALL_DIR:-}" ]; then
      _dir="${HOME}/.local/bin"
      warn "/usr/local/bin is not writable and sudo is unavailable; installing to ${_dir}"
      mkdir -p "$_dir"
    fi
  fi

  info "Installing to ${_dir}/bosca"
  if [ -w "$_dir" ] || { [ ! -e "$_dir" ] && mkdir -p "$_dir" 2>/dev/null; }; then
    install -m 0755 "$_bin" "${_dir}/bosca" 2>/dev/null || cp "$_bin" "${_dir}/bosca"
  else
    as_root install -m 0755 "$_bin" "${_dir}/bosca"
  fi
  INSTALLED_PATH="${_dir}/bosca"

  case ":${PATH}:" in
    *":${_dir}:"*) : ;;
    *) warn "${_dir} is not on your PATH; add it to use 'bosca' directly" ;;
  esac
}

case "$FILENAME" in
  *.pkg)            install_pkg ;;
  *.tar.gz|*.tgz)   install_tarball ;;
  *) die "don't know how to install '${FILENAME}' (unrecognized package type)" ;;
esac

# ── Confirm ─────────────────────────────────────────────────────────────────
info ""
if [ -x "${INSTALLED_PATH:-}" ] || command -v bosca >/dev/null 2>&1; then
  info "✓ Bosca CLI ${VERSION} installed to ${INSTALLED_PATH:-$(command -v bosca)}"
  if command -v bosca >/dev/null 2>&1; then
    info "  $(bosca --version 2>/dev/null || echo "run 'bosca --help' to get started")"
  fi
else
  warn "installation finished but 'bosca' was not found on PATH; check ${INSTALLED_PATH:-/usr/local/bin/bosca}"
fi
