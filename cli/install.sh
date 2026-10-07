#!/bin/sh
# shellcheck shell=sh
#
# Bosca CLI installer.
#
#   curl -fsSL https://bosca.io/cli/install.sh | sh
#
# Installs the latest released `bosca` binary for your platform from the
# project's GitHub Releases. No login or token is required.
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
#
# Environment overrides
# ---------------------
#   BOSCA_VERSION          Install this exact version instead of the latest.
#   BOSCA_INSTALL_DIR      Where to place the binary for tarball installs
#                          (default: /usr/local/bin). The macOS .pkg always
#                          installs to /usr/local/bin (baked into the package).
#   BOSCA_CLI_REPOSITORY   Read releases from this owner/name repository
#                          instead of bosca-io/bosca (for example a fork).
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

# ── Output helpers ──────────────────────────────────────────────────────────
# Status goes to stderr so a future `... | sh` that wants to capture stdout is
# never polluted, and so messages interleave correctly with sub-command output.
info() { printf '%s\n' "$*" >&2; }
warn() { printf 'warning: %s\n' "$*" >&2; }
err()  { printf 'error: %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

# ── Prerequisites ───────────────────────────────────────────────────────────
# A single downloader abstraction so the rest of the script doesn't care whether
# curl or wget is present. `http_get URL [extra-args...]` writes the body to
# stdout and returns non-zero on any HTTP/transport error.
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
# http_get_to_stdout URL
http_get_to_stdout() {
  _url="$1"
  _auth=""
  case "$_url" in
    https://api.github.com/*) [ -n "${GITHUB_TOKEN:-}" ] && _auth="Authorization: Bearer ${GITHUB_TOKEN}" ;;
  esac
  if [ "$DOWNLOADER" = "curl" ]; then
    if [ -n "$_auth" ]; then
      curl -fsSL --retry 3 -H "Accept: application/vnd.github+json" -H "$_auth" "$_url"
    else
      curl -fsSL --retry 3 -H "Accept: application/vnd.github+json" "$_url"
    fi
  else
    if [ -n "$_auth" ]; then
      wget -qO- --header="Accept: application/vnd.github+json" --header="$_auth" "$_url"
    else
      wget -qO- --header="Accept: application/vnd.github+json" "$_url"
    fi
  fi
}

# http_get_to_file URL FILE
http_get_to_file() {
  _url="$1"; _file="$2"
  if [ "$DOWNLOADER" = "curl" ]; then
    curl -fsSL --retry 3 -o "$_file" "$_url"
  else
    wget -qO "$_file" "$_url"
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
else
  LIST_URL="${API_URL}/releases?per_page=100"
  info "Querying ${LIST_URL}"
  LISTING="$(http_get_to_stdout "$LIST_URL")" || die "failed to list releases of ${REPOSITORY} (set GITHUB_TOKEN if rate limited)"
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
RELEASE="$(http_get_to_stdout "${API_URL}/releases/tags/${TAG}")" || die "release ${TAG} was not found in ${REPOSITORY}"
ASSETS="$(printf '%s' "$RELEASE" | grep -o '"name": *"bosca-[^"]*"' | sed 's/.*"name": *"//; s/"$//' | sort -u)"
PREFIX="bosca-${VERSION}-${PLATFORM}"
# Escape regex metacharacters (the version's dots in particular) so the prefix is
# matched literally rather than as a pattern.
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
info "Selected package: ${FILENAME}"

# ── Download ────────────────────────────────────────────────────────────────
TMPDIR_INSTALL="$(mktemp -d 2>/dev/null || mktemp -d -t bosca-install)"
# Clean up the scratch directory on any exit path.
trap 'rm -rf "$TMPDIR_INSTALL"' EXIT INT TERM
PKG_PATH="${TMPDIR_INSTALL}/${FILENAME}"
SUMS_PATH="${TMPDIR_INSTALL}/SHA256SUMS"
DOWNLOAD_URL="${DOWNLOAD_BASE}/${TAG}/${FILENAME}"

info "Downloading ${DOWNLOAD_URL}"
http_get_to_file "$DOWNLOAD_URL" "$PKG_PATH" || die "download failed"
[ -s "$PKG_PATH" ] || die "downloaded file is empty"
http_get_to_file "${DOWNLOAD_BASE}/${TAG}/SHA256SUMS" "$SUMS_PATH" || die "release ${TAG} has no SHA256SUMS asset"

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
