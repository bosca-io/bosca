"""Shared file-only archive helpers for isolated harness qualification probes.

Callers select files and stop every writer before staging them here. These helpers
do not interpret native state or implement the production checkpoint lifecycle.
"""

import hashlib
import json
import tarfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def archive_files(checkpoint, metadata, forbidden_values=()):
    """Archive detached regular-file fixtures with a versioned checksum manifest."""
    assert not (checkpoint / "manifest.json").exists(), "Staging directory already has a manifest"
    files = {}
    for path in sorted(checkpoint.rglob("*")):
        assert not path.is_symlink(), "Symlink restoration is outside this qualification"
        if path.is_dir():
            continue
        assert path.is_file(), "Fixture contains a non-regular file"
        for value in forbidden_values:
            assert value not in path.read_bytes(), "Injected credential found in snapshot"
        files[str(path.relative_to(checkpoint))] = {
            "sha256": digest(path), "size": path.stat().st_size,
        }
    manifest = {"formatVersion": 1, "metadata": metadata, "files": files}
    (checkpoint / "manifest.json").write_text(json.dumps(manifest, indent=2))
    archive = checkpoint.with_suffix(".tar.gz")
    with tarfile.open(archive, "w:gz", format=tarfile.PAX_FORMAT) as bundle:
        bundle.add(checkpoint, arcname="checkpoint")
    return {"files": files, "archiveSha256": digest(archive),
            "archiveBytes": archive.stat().st_size}


def extract_files(archive, destination, expected_digest):
    """Verify and extract a probe archive into a fresh checkpoint directory."""
    assert digest(archive) == expected_digest, "Archive checksum mismatch"
    checkpoint = destination / "checkpoint"
    assert not checkpoint.exists(), "Restore destination must be empty"
    with tarfile.open(archive) as bundle:
        bundle.extractall(destination, filter="data")
    manifest = json.loads((checkpoint / "manifest.json").read_text())
    assert manifest["formatVersion"] == 1
    for name, info in manifest["files"].items():
        path = checkpoint / name
        assert path.resolve().is_relative_to(checkpoint.resolve())
        assert path.is_file() and not path.is_symlink()
        assert path.stat().st_size == info["size"] and digest(path) == info["sha256"]
    actual = {str(path.relative_to(checkpoint)) for path in checkpoint.rglob("*") if path.is_file()}
    assert actual == set(manifest["files"]) | {"manifest.json"}
    return checkpoint
