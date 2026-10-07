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


if __name__ == "__main__":
    unittest.main()
