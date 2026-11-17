"""Check the serving switch through standalone and umbrella chart rendering."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[1]


class TfServingProvisioningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        stage = tempfile.TemporaryDirectory(prefix="serving-charts-")
        cls.addClassCleanup(stage.cleanup)
        cls.root = Path(stage.name) / "helm"
        shutil.copytree(ROOT, cls.root, ignore=shutil.ignore_patterns("charts", "__pycache__"))
        umbrella = cls.root / "bosca-services"
        dependencies = yaml.safe_load((umbrella / "Chart.yaml").read_text())["dependencies"]
        for chart in [cls.root / d["name"] for d in dependencies] + [umbrella]:
            result = subprocess.run(["helm", "dependency", "build", str(chart), "--skip-refresh"],
                                    capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)

    def render(self, chart, *options):
        output = subprocess.run(["helm", "template", "test", str(self.root / chart),
                                 "--set", "global.domain=example.com", *options],
                                check=True, capture_output=True, text=True).stdout
        return [d for d in yaml.safe_load_all(output) if d]

    def serving_documents(self, chart, *options):
        return [d for d in self.render(chart, *options)
                if d["metadata"].get("labels", {}).get("app.kubernetes.io/name") == "tf-serving"]

    def test_disabled_creates_nothing_even_with_former_enable_flag(self):
        for chart in ("tf-serving", "bosca-services"):
            prefix = "tf-serving." if chart == "bosca-services" else ""
            for options in ((), ("--set", prefix + "enabled=true"),
                            ("--set", prefix + "provisioning.enabled=false")):
                with self.subTest(chart=chart, options=options):
                    self.assertEqual([], self.serving_documents(chart, *options))

    def test_enabled_creates_only_profile_and_service_account(self):
        for chart in ("tf-serving", "bosca-services"):
            prefix = "tf-serving." if chart == "bosca-services" else ""
            with self.subTest(chart=chart):
                documents = self.serving_documents(
                    chart, "--set", prefix + "provisioning.enabled=true",
                    "--set", prefix + "resources.limits.memory=6Gi")
                self.assertCountEqual(["ServingModelProfile", "ServiceAccount"],
                                      [d["kind"] for d in documents])
                profile = next(d for d in documents if d["kind"] == "ServingModelProfile")
                pod = profile["spec"]["podTemplate"]["spec"]
                self.assertEqual("6Gi", pod["containers"][0]["resources"]["limits"]["memory"])
                self.assertEqual(1, len(pod["containers"]))
                self.assertEqual("download-models", pod["initContainers"][0]["name"])
                self.assertEqual([{"name": "models", "emptyDir": {}}], pod["volumes"])


if __name__ == "__main__":
    unittest.main()
