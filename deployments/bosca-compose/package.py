#!/usr/bin/env python3
"""Build a portable Bosca Compose archive from an explicit list of public package files."""

import argparse
import gzip
import hashlib
import io
from pathlib import Path
import re
import tarfile

from installer import PACKAGE_FILES, ROOT


def build(output: Path, source: Path = ROOT) -> Path:
    version = (source / "VERSION").read_text().strip()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.-]+)?", version):
        raise ValueError("VERSION must contain a package version such as 0.1.0.")
    files = {}
    for name in PACKAGE_FILES:
        path = source / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing or unsupported package file: {name}")
        files[name] = path.read_bytes()
    output.mkdir(parents=True, exist_ok=True)
    prefix = f"bosca-compose-{version}"
    archive = output / f"{prefix}.tar.gz"
    with archive.open("wb") as raw, gzip.GzipFile(fileobj=raw, filename="", mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as tar:
            for name, content in sorted(files.items()):
                info = tarfile.TarInfo(f"{prefix}/{name}")
                info.size = len(content)
                info.mode = 0o755 if name.endswith(".sh") else 0o644
                tar.addfile(info, io.BytesIO(content))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_name(archive.name + ".sha256").write_text(f"{digest}  {archive.name}\n")
    return archive


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    try:
        print(build(args.output.expanduser().resolve()))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Packaging failed: {error}\n")
