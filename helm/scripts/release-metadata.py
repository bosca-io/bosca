"""Stamp staged charts with a release version and rebuild their dependency locks."""

from pathlib import Path
import re
import subprocess
import sys

import yaml


def read_yaml(path):
    return yaml.safe_load(path.read_text())


def write_yaml(path, data):
    path.write_text(yaml.safe_dump(data, sort_keys=False))


def bosca_image(image):
    return image.get("registry", "").rstrip("/") == "ghcr.io/bosca-io/bosca"


def stamp_images(values, version):
    changed = False
    if isinstance(values, dict):
        image = values.get("image")
        if isinstance(image, dict) and bosca_image(image) and image.get("tag"):
            image["tag"] = version
            changed = True
        for value in values.values():
            changed = stamp_images(value, version) or changed
    elif isinstance(values, list):
        for value in values:
            changed = stamp_images(value, version) or changed
    return changed


def stage(charts, version):
    number = r"(?:0|[1-9][0-9]*)"
    identifier = rf"(?:{number}|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)"
    # Release versions also become container image tags, which cannot contain '+'.
    semver = rf"{number}\.{number}\.{number}(?:-{identifier}(?:\.{identifier})*)?"
    if not re.fullmatch(semver, version):
        raise ValueError("Release version must be a bare semantic version, such as 6.31.0 or 6.31.0-rc.1")

    for path in sorted(charts.glob("*/Chart.yaml")):
        chart = read_yaml(path)
        chart["version"] = version
        values_path = path.with_name("values.yaml")
        if values_path.exists():
            values = read_yaml(values_path) or {}
            if bosca_image(values.get("image", {})):
                chart["appVersion"] = version
            if stamp_images(values, version):
                write_yaml(values_path, values)
        dependencies = chart.get("dependencies", [])
        if dependencies:
            locked = read_yaml(path.with_name("Chart.lock"))["dependencies"]
            for dependency in dependencies:
                if dependency.get("repository", "").startswith("file://"):
                    dependency["version"] = version
                else:
                    matches = [entry for entry in locked if
                               entry["name"] == dependency["name"] and
                               entry["repository"] == dependency.get("repository", "")]
                    if len(matches) != 1:
                        raise ValueError(f"Missing or ambiguous dependency lock: {path.parent.name}/{dependency['name']}")
                    # Keep upstream releases fixed even when Chart.yaml uses a range.
                    dependency["version"] = matches[0]["version"]
        write_yaml(path, chart)


def dependencies(chart, source):
    subprocess.run(["helm", "dependency", "update", str(chart), "--skip-refresh"], check=True)
    lock_path = chart / "Chart.lock"
    lock = read_yaml(lock_path)
    # A rebuilt release must have identical chart files for publication retries.
    # Helm computes the new digest; retain the source timestamp instead of now.
    lock["generated"] = read_yaml(source / "Chart.lock")["generated"]
    write_yaml(lock_path, lock)


if __name__ == "__main__":
    try:
        command, directory, argument = sys.argv[1:]
        if command == "stage":
            stage(Path(directory), argument)
        elif command == "dependencies":
            dependencies(Path(directory), Path(argument))
        else:
            raise ValueError(f"Unknown command: {command}")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Release failed: {error}")
