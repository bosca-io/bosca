#!/usr/bin/env python3
"""Install the Bosca Compose files and generate configuration without starting services."""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import tempfile

ROOT = Path(__file__).resolve().parent
MARKER = ".bosca-compose-install.json"
PACKAGE_FILES = (
    ".gitignore", "VERSION", "README.md", "install.sh", "installer.py",
    "package.sh", "package.py", "compose.sh", "compose.yaml",
    "config/server.yaml", "config/git.yaml", "config/nats.conf",
    "config/nginx.conf.template", "config/nginx-bootstrap.conf.template",
    "config/nginx-proxy.conf", "config/certbot-deploy.sh",
)
DATA_DIRS = (
    "postgres", "nats", "meilisearch", "storage", "server-tmp", "git-tmp", "message-cache",
)
PORTS = {"API_PORT": "8080", "GIT_PORT": "8091", "STUDIO_PORT": "3000", "BML_PORT": "9093"}
SECRET_KEYS = (
    "DATABASE_PASSWORD", "JWT_SECRET", "SECURITY_ENCRYPTION_KEY",
    "STORAGE_URL_SIGNER_SECRET_KEY", "NATS_TOKEN", "MEILISEARCH_API_KEY",
    "INIT_ADMIN_PASSWORD", "INIT_SA_PASSWORD",
)
PUBLIC_KEYS = {"BOSCA_DOMAIN", "STUDIO_DOMAIN", "GIT_DOMAIN", "CERT_NAME", *PORTS}


def hostname(value: str) -> str:
    """Validate an ASCII DNS name used in URLs and nginx server names."""
    value = value.lower()
    labels = value.split(".")
    if len(value) > 253 or len(labels) < 2 or any(
        not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels
    ):
        raise ValueError("Domains must be DNS names such as example.com, without a scheme, path, or port.")
    return value


def validate_settings(settings: dict[str, str]) -> dict[str, str]:
    result = dict(settings)
    for key in ("BOSCA_DOMAIN", "STUDIO_DOMAIN", "GIT_DOMAIN"):
        result[key] = hostname(result[key])
    domain = result["BOSCA_DOMAIN"]
    for key in ("STUDIO_DOMAIN", "GIT_DOMAIN"):
        host = result[key]
        if host != domain and not host.endswith("." + domain):
            raise ValueError("Studio and Git must share BOSCA_DOMAIN for authentication cookies.")
    if result["STUDIO_DOMAIN"] == result["GIT_DOMAIN"]:
        raise ValueError("Studio and Git need distinct hostnames.")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", result["CERT_NAME"]):
        raise ValueError("CERT_NAME must contain only lowercase letters, numbers, hyphens, or underscores.")
    ports = []
    for key in PORTS:
        value = result[key]
        if not value.isascii() or not value.isdecimal() or not 1 <= int(value) <= 65535:
            raise ValueError(f"{key} must be a TCP port between 1 and 65535.")
        result[key] = str(int(value))
        ports.append(int(value))
    if len(set(ports)) != len(ports) or {80, 443}.intersection(ports):
        raise ValueError("Application ports must be distinct and leave ports 80 and 443 for nginx.")
    return result


def read_public_settings(directory: Path) -> dict[str, str]:
    # Read only public settings. Never source .env as shell code or inspect credential values.
    settings = dict(PORTS)
    settings["CERT_NAME"] = "bosca"
    for line in (directory / ".env").read_text().splitlines():
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or key not in PUBLIC_KEYS:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        settings[key] = value
    missing = PUBLIC_KEYS - settings.keys()
    if missing:
        raise ValueError("Missing public settings in .env: " + ", ".join(sorted(missing)))
    return validate_settings(settings)


def write_atomic(path: Path, text: str, mode: int = 0o644) -> None:
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "w") as output:
            output.write(text)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def render_nginx(directory: Path, settings: dict[str, str]) -> None:
    settings = validate_settings(settings)
    rendered = {}
    for name in ("nginx.conf", "nginx-bootstrap.conf"):
        content = (directory / "config" / (name + ".template")).read_text()
        for key, value in settings.items():
            content = content.replace("@@" + key + "@@", value)
        if re.search(r"@@[A-Z_]+@@", content):
            raise ValueError(f"Unresolved placeholders in {name}.template")
        rendered[name] = content
    for name, content in rendered.items():
        write_atomic(directory / "config" / name, content)


def environment(settings: dict[str, str]) -> str:
    public = {
        "COMPOSE_PROJECT_NAME": "bosca",
        **settings,
        "APP_URL": "https://${STUDIO_DOMAIN}",
        "GIT_URL": "https://${GIT_DOMAIN}",
        "WS_URL": "wss://${STUDIO_DOMAIN}",
        "JWT_COOKIE_SECURE": "true",
        "BOSCA_IMAGE_REGISTRY": "ghcr.io/bosca-io/bosca",
        "SERVER_VERSION": "6.28.2",
        "GIT_VERSION": "6.27.7",
        "STUDIO_VERSION": "6.28.4",
        "BML_MESSAGE_VERSION": "6.23.0",
        "BML_MESSAGE_ARTIFACTS_URL": "",
        "BML_MESSAGE_ARTIFACTS_TOKEN": "",
    }
    text = "# Generated by Bosca Compose. Keep this file private.\n"
    text += "\n".join(f"{key}={value}" for key, value in public.items()) + "\n"
    text += "\n# Fresh installation credentials\n"
    text += "\n".join(f"{key}={secrets.token_hex(32)}" for key in SECRET_KEYS) + "\n"
    text += "PIPELINE_SECRET_KEY=" + base64.b64encode(secrets.token_bytes(32)).decode() + "\n"
    return text


def install(directory: Path, settings: dict[str, str] | None, source: Path = ROOT) -> bool:
    """Create a fresh installation atomically; never overwrite an existing installation."""
    version = (source / "VERSION").read_text().strip()
    marker = directory / MARKER
    if marker.is_file():
        installed = json.loads(marker.read_text())
        if installed.get("package") != "bosca-compose" or installed.get("version") != version:
            raise ValueError("An existing installation has a different package/version. See the upgrade instructions.")
        if settings and validate_settings(settings) != read_public_settings(directory):
            raise ValueError("Existing settings differ. Edit .env and use --render-nginx to change hostnames or ports.")
        return False
    if directory.exists() and (not directory.is_dir() or any(directory.iterdir())):
        raise ValueError("The installation directory must be empty; existing files will not be overwritten.")
    if settings is None:
        raise ValueError("--domain is required for a new installation.")
    settings = validate_settings(settings)
    for name in PACKAGE_FILES:
        path = source / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing or unsupported package file: {name}")
    directory.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".bosca-install-", dir=directory.parent))
    try:
        for name in PACKAGE_FILES:
            destination = staging / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, destination)
            destination.chmod(0o755 if name.endswith(".sh") else 0o644)
        write_atomic(staging / ".env", environment(settings), 0o600)
        for name in DATA_DIRS:
            (staging / "data" / name).mkdir(parents=True)
        (staging / ".docker").mkdir(mode=0o700)
        render_nginx(staging, settings)
        write_atomic(staging / MARKER, json.dumps({"package": "bosca-compose", "version": version}) + "\n", 0o600)
        staging.chmod(0o755)
        os.replace(staging, directory)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("/opt/bosca"), help="installation directory (default: /opt/bosca)")
    parser.add_argument("--domain", help="shared cookie domain, for example example.com")
    parser.add_argument("--studio-domain", help="default: studio.<domain>")
    parser.add_argument("--git-domain", help="default: git.<domain>")
    parser.add_argument("--cert-name", help="Certbot certificate name (default: bosca)")
    parser.add_argument("--render-nginx", action="store_true", help="regenerate nginx files from the installation's public .env settings")
    for key, default in PORTS.items():
        parser.add_argument("--" + key.lower().replace("_", "-"), help=f"loopback port (default: {default})")
    args = parser.parse_args()
    directory = args.directory.expanduser().resolve()
    try:
        custom_settings = any((args.domain, args.studio_domain, args.git_domain, args.cert_name)) or any(
            getattr(args, key.lower()) is not None for key in PORTS
        )
        if args.render_nginx:
            if custom_settings:
                raise ValueError("--render-nginx reads settings from .env; edit that file first.")
            render_nginx(directory, read_public_settings(directory))
            print(f"Rendered nginx files in {directory / 'config'}.")
            return
        if custom_settings and not args.domain:
            raise ValueError("--domain is required with custom installation settings.")
        settings = None
        if args.domain:
            domain = hostname(args.domain)
            settings = {
                "BOSCA_DOMAIN": domain,
                "STUDIO_DOMAIN": args.studio_domain or "studio." + domain,
                "GIT_DOMAIN": args.git_domain or "git." + domain,
                "CERT_NAME": args.cert_name or "bosca",
                **{key: getattr(args, key.lower()) or default for key, default in PORTS.items()},
            }
        created = install(directory, settings)
        print(f"Installed Bosca Compose in {directory}." if created else f"Already installed in {directory}; existing files preserved.")
        print("Set BML_MESSAGE_ARTIFACTS_URL and BML_MESSAGE_ARTIFACTS_TOKEN in .env, then follow README.md for HTTPS and startup.")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Installation failed: {error}\n")


if __name__ == "__main__":
    main()
