"""Check the local stack's resolved image references without starting containers."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(shutil.which("docker"), "Docker CLI is unavailable")
class LocalComposeTest(unittest.TestCase):
    def resolve(self, env_file, **overrides):
        environment = {key: value for key, value in os.environ.items()
                       if key in ("PATH", "HOME", "DOCKER_CLI_PLUGIN_EXTRA_DIRS")}
        environment.update(BOSCA_ATLAS_VERSION="fixture", **overrides)
        result = subprocess.run(["docker", "compose", "--project-directory", str(ROOT),
                                 "--env-file", str(env_file), "-f", str(ROOT / "docker-compose.yaml"),
                                 "config", "--format", "json"], env=environment, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)["services"]

    def test_public_atlas_namespace_with_and_without_the_example_environment(self):
        for env_file in (Path(os.devnull), ROOT / ".env.example"):
            with self.subTest(env_file=env_file):
                services = self.resolve(env_file)
                self.assertEqual("ghcr.io/bosca-io/bosca/atlas-server:fixture", services["atlas-server"]["image"])

    def test_atlas_image_mirror_is_independent_of_existing_image_defaults(self):
        services = self.resolve(ROOT / ".env.example", BOSCA_IMAGE_REGISTRY="existing.example.test/images",
                                BOSCA_ATLAS_IMAGE_REGISTRY="registry.example.test/bosca")
        self.assertEqual("registry.example.test/bosca/atlas-server:fixture", services["atlas-server"]["image"])
        self.assertTrue(services["bosca-server"]["image"].startswith("existing.example.test/images/"))


if __name__ == "__main__":
    unittest.main()
