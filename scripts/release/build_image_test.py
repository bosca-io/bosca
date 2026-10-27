"""Exercise release commands without running Gradle, Docker, or publishing images."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BuildImageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        script = self.root / "scripts/release/build-image.sh"
        script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name("build-image.sh"), script)
        self.script = script
        self.commands = self.root / "commands"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_executable(self.bin / "uname", '#!/bin/bash\ncase "$1" in -s) echo Linux;; -m) echo x86_64;; esac\n')
        self.write_executable(self.bin / "docker", '#!/bin/bash\nprintf "docker %s\\n" "$*" >> "$COMMAND_LOG"\n')
        self.write_executable(self.bin / "npm", '#!/bin/bash\nprintf "npm %s\\n" "$*" >> "$COMMAND_LOG"\n')
        (self.root / "bml/bml-runtime").mkdir(parents=True)
        self.write_executable(self.root / "gradlew", """#!/bin/bash
printf 'gradle %s\\n' "$*" >> "$COMMAND_LOG"
case "$*" in
  *:server:bosca-server:nativeCompile*)
    mkdir -p server/bosca-server/build/native/nativeCompile
    printf native > server/bosca-server/build/native/nativeCompile/bosca-server
    ;;
  *:server:bosca-runner:installDist*)
    mkdir -p server/bosca-runner/build/install/bosca-runner
    printf jvm > server/bosca-runner/build/install/bosca-runner/runner
    ;;
  *:analytics:analytics-collector:nativeCompile*)
    mkdir -p analytics/analytics-collector/build/native/nativeCompile
    printf native > analytics/analytics-collector/build/native/nativeCompile/analytics-collector
    ;;
  *:git:git-server:nativeCompile*)
    mkdir -p git/git-server/build/native/nativeCompile
    printf native > git/git-server/build/native/nativeCompile/bosca-git-server
    ;;
  *:bml:bml-design-catalog:nativeCompile*)
    mkdir -p bml/bml-design-catalog/build/native/nativeCompile
    printf native > bml/bml-design-catalog/build/native/nativeCompile/bml-design-catalog
    ;;
esac
""")
        self.environment = {
            **os.environ,
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "COMMAND_LOG": str(self.commands),
            "PUSH": "false",
            "BOSCA_IMAGE_REGISTRY": "example.invalid/bosca",
            "BOSCA_REGISTRY_URL": "",
        }

    @staticmethod
    def write_executable(path, content):
        path.write_text(content)
        path.chmod(0o755)

    def build(self, image):
        subprocess.run(
            ["bash", str(self.script), image, "test"],
            env=self.environment,
            check=True,
            capture_output=True,
            text=True,
        )
        return self.commands.read_text().splitlines()

    def test_native_server_disables_local_scripting(self):
        commands = self.build("bosca-server")
        self.assertEqual(
            commands[0],
            "gradle --no-daemon --no-configuration-cache -Pbosca.scripting.engine=false :server:bosca-server:nativeCompile",
        )
        self.assertEqual(
            (self.root / "server/artifacts/release-test/bosca-server-native/bosca-server").read_text(),
            "native",
        )
        self.assertIn("--build-arg ARTIFACT_SHA=release-test", commands[1])
        self.assertEqual(len(commands), 2)

    def test_jvm_runner_retains_local_scripting(self):
        commands = self.build("bosca-runner")
        self.assertEqual(commands[0], "gradle --no-daemon :server:bosca-runner:installDist")
        self.assertEqual(
            (self.root / "server/artifacts/release-test/bosca-runner/runner").read_text(),
            "jvm",
        )
        self.assertEqual(len(commands), 2)

    def test_other_native_images_do_not_receive_server_property(self):
        commands = self.build("analytics-collector")
        self.assertEqual(
            commands[0],
            "gradle --no-daemon --no-configuration-cache :analytics:analytics-collector:nativeCompile",
        )
        self.assertEqual(len(commands), 2)

    def test_design_catalog_stages_the_native_executable_without_pushing(self):
        self.assertEqual(self.build("bml-design-catalog"), [
            "npm ci --no-audit --no-fund",
            "gradle --no-daemon --no-configuration-cache :bml:bml-design-catalog:nativeCompile",
            "docker build --platform linux/amd64 -f bml/bml-design-catalog/Dockerfile "
            "-t example.invalid/bosca/bml-design-catalog:test --build-arg ARTIFACT_SHA=release-test bml",
        ])
        self.assertEqual((self.root / "bml/artifacts/release-test/bml-design-catalog-native/bml-design-catalog").read_text(), "native")

    def test_git_installs_runtime_dependencies_before_native_compilation(self):
        self.environment["RUNTIME_DIRECTORY"] = str(self.root / "bml/bml-runtime")
        self.write_executable(self.bin / "npm", """#!/bin/bash
[[ "$PWD" == "$RUNTIME_DIRECTORY" ]] || exit 1
printf 'npm %s\\n' "$*" >> "$COMMAND_LOG"
""")
        self.assertEqual(self.build("git-server"), [
            "npm ci --no-audit --no-fund",
            "gradle --no-daemon --no-configuration-cache :git:git-server:nativeCompile",
            "docker build --platform linux/amd64 -f git/git-server/Dockerfile "
            "-t example.invalid/bosca/git-server:test --build-arg ARTIFACT_SHA=release-test git",
        ])
        self.assertEqual((self.root / "git/artifacts/release-test/git-server-native/bosca-git-server").read_text(), "native")

    def test_git_stops_when_runtime_installation_fails(self):
        self.write_executable(self.bin / "npm", '#!/bin/bash\nexit 42\n')
        result = subprocess.run(["bash", str(self.script), "git-server", "test"],
                                env=self.environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 42)
        self.assertFalse(self.commands.exists())

    def test_git_rejects_non_linux_builds_before_installation(self):
        self.write_executable(self.bin / "uname", '#!/bin/bash\ncase "$1" in -s) echo Darwin;; -m) echo arm64;; esac\n')
        result = subprocess.run(["bash", str(self.script), "git-server", "test"],
                                env=self.environment, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Linux x86_64 host", result.stderr)
        self.assertFalse(self.commands.exists())

    def test_design_catalog_rejects_non_linux_builds_before_staging(self):
        self.write_executable(self.bin / "uname", '#!/bin/bash\ncase "$1" in -s) echo Darwin;; -m) echo arm64;; esac\n')
        result = subprocess.run(["bash", str(self.script), "bml-design-catalog", "test"],
                                env=self.environment, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Linux x86_64 host", result.stderr)
        self.assertFalse(self.commands.exists())


if __name__ == "__main__":
    unittest.main()
