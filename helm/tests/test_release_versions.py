"""Verify release metadata and rendered image defaults using the workspace charts."""

from pathlib import Path
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest

import yaml


HELM_ROOT = Path(__file__).resolve().parents[1]
VERSION = "9.8.7-rc.1"


def run(*arguments):
    result = subprocess.run(arguments, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(f"{' '.join(map(str, arguments))}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def snapshot(directory):
    return {str(path.relative_to(directory)): path.read_bytes()
            for path in directory.rglob("*") if path.is_file()}


class ReleaseVersionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory(prefix="bosca-release-versions-")
        cls.addClassCleanup(temporary.cleanup)
        cls.root = Path(temporary.name)
        cls.charts = cls.root / "charts"
        cls.packages = cls.root / "packages"
        # These charts have only local dependencies, so the packaging test is offline.
        for metadata in HELM_ROOT.glob("*/Chart.yaml"):
            if metadata.parent.name != "bosca-infra":
                shutil.copytree(metadata.parent, cls.charts / metadata.parent.name,
                                ignore=shutil.ignore_patterns("charts"))
        cls.before = snapshot(cls.charts)
        run("bash", str(HELM_ROOT / "scripts/release.sh"), "package",
            "--version", VERSION, "--charts", str(cls.charts), "--output", str(cls.packages))

    def package(self, name):
        return self.packages / f"{name}-{VERSION}.tgz"

    def render(self, name, *settings):
        return run("helm", "template", "test", str(self.package(name)),
                   "--set", "global.domain=example.com", *settings)

    def test_every_chart_and_nested_local_dependency_uses_release_version(self):
        for package in self.packages.glob("*.tgz"):
            with self.subTest(package=package.name), tarfile.open(package) as archive:
                for member in archive.getmembers():
                    if member.name.endswith("/Chart.yaml"):
                        chart = yaml.safe_load(archive.extractfile(member))
                        self.assertEqual(VERSION, chart["version"], member.name)
                        for dependency in chart.get("dependencies", []):
                            self.assertEqual(VERSION, dependency["version"], member.name)
                    elif member.name.endswith("/Chart.lock"):
                        lock = yaml.safe_load(archive.extractfile(member))
                        for dependency in lock["dependencies"]:
                            self.assertEqual(VERSION, dependency["version"], member.name)

    def test_bosca_application_versions_and_default_images_follow_release(self):
        for path in self.charts.glob("*/values.yaml"):
            values = yaml.safe_load(path.read_text())
            image = values.get("image", {})
            if image.get("registry") != "ghcr.io/bosca-io/bosca":
                continue
            name = path.parent.name
            with self.subTest(chart=name):
                metadata = yaml.safe_load(run("helm", "show", "chart", str(self.package(name))))
                self.assertEqual(VERSION, metadata["appVersion"])
                rendered = self.render(name, "--set", "appConfig.publicUrl=https://messages.example.com")
                self.assertIn(f"ghcr.io/bosca-io/bosca/{image['repository']}:{VERSION}", rendered)

    def test_upstream_images_keep_versions_and_loader_follows_release(self):
        rendered = self.render("tf-serving")
        self.assertIn("tensorflow/serving:2.16.1", rendered)
        self.assertIn(f"ghcr.io/bosca-io/bosca/recommendation-model-loader:{VERSION}", rendered)
        rendered = self.render("text-embeddings-inference")
        self.assertIn("ghcr.io/huggingface/text-embeddings-inference:cpu-1.8.1", rendered)

    def test_umbrella_and_sidecar_image_overrides_take_precedence(self):
        rendered = self.render("bosca-services", "--set", "bosca-server.image.tag=custom")
        self.assertIn("ghcr.io/bosca-io/bosca/bosca-server:custom", rendered)
        rendered = self.render("tf-serving", "--set", "loader.image.tag=custom")
        self.assertIn("ghcr.io/bosca-io/bosca/recommendation-model-loader:custom", rendered)

    def test_source_charts_values_and_locks_are_unchanged(self):
        self.assertEqual(self.before, snapshot(self.charts))

    def test_remote_version_ranges_are_pinned_to_checked_in_locks(self):
        charts = self.root / "upstream"
        source = HELM_ROOT / "bosca-infra"
        chart = charts / "bosca-infra"
        shutil.copytree(source, chart, ignore=shutil.ignore_patterns("charts"))
        metadata_path = chart / "Chart.yaml"
        metadata = yaml.safe_load(metadata_path.read_text())
        for dependency in metadata["dependencies"]:
            dependency["version"] = ">=0.0.0"
        metadata_path.write_text(yaml.safe_dump(metadata))
        run("python3", str(HELM_ROOT / "scripts/release-metadata.py"), "stage", str(charts), VERSION)
        stamped = yaml.safe_load(metadata_path.read_text())
        locked = yaml.safe_load((source / "Chart.lock").read_text())["dependencies"]
        versions = {dependency["name"]: dependency["version"] for dependency in locked}
        for dependency in stamped["dependencies"]:
            expected = VERSION if dependency["repository"].startswith("file://") else versions[dependency["name"]]
            self.assertEqual(expected, dependency["version"])


class PipelineReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        pipeline = yaml.safe_load((HELM_ROOT.parent / ".bosca/pipelines/release-helm.yaml").read_text())
        self.steps = {step["name"]: step for step in pipeline["jobs"]["publish-charts"]["steps"]}

    def test_selected_tag_is_passed_to_packaging(self):
        step = self.steps["Package charts"]
        self.assertEqual("${{ ref }}", step["env"]["RELEASE_REF"])
        with tempfile.TemporaryDirectory(prefix="bosca-release-pipeline-") as temporary:
            root = Path(temporary)
            script = root / "helm/scripts/release.sh"
            script.parent.mkdir(parents=True)
            script.write_text('printf "%s\\n" "$@"\n')
            result = subprocess.run(["bash", "-eu", "-c", step["run"]], cwd=root,
                                    env={**os.environ, "RELEASE_REF": f"refs/tags/{VERSION}"},
                                    check=True, capture_output=True, text=True)
            self.assertEqual(["package", "--version", VERSION, "--output", ".helm-release-packages"],
                             result.stdout.splitlines())

    def test_branch_release_is_rejected_before_checkout(self):
        step = self.steps["Validate release tag"]
        result = subprocess.run(["bash", "-eu", "-c", step["run"]],
                                env={**os.environ, "RELEASE_REF": "refs/heads/main"},
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Select an existing Git tag", result.stderr)


if __name__ == "__main__":
    unittest.main()
