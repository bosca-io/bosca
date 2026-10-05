"""Exercise fresh installs, preservation, archive contents, and the Compose launcher."""

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import installer
import package


def settings(**overrides):
    return {
        "BOSCA_DOMAIN": "example.com",
        "STUDIO_DOMAIN": "studio.example.com",
        "GIT_DOMAIN": "git.example.com",
        "CERT_NAME": "bosca",
        **installer.PORTS,
        **overrides,
    }


def snapshot(directory):
    return {
        str(path.relative_to(directory)): (path.read_bytes(), stat.S_IMODE(path.stat().st_mode))
        for path in directory.rglob("*") if path.is_file()
    }


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bosca-package-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.destination = self.root / "installation with spaces"

    def install(self, **overrides):
        self.assertTrue(installer.install(self.destination, settings(**overrides)))
        return self.destination

    def source_copy(self):
        source = self.root / "source"
        for name in installer.PACKAGE_FILES:
            destination = source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
        return source

    def test_fresh_install_prepares_private_credentials_and_all_mounts(self):
        directory = self.install()
        env_path = directory / ".env"
        env = dict(line.split("=", 1) for line in env_path.read_text().splitlines() if "=" in line)
        self.assertEqual(0o600, stat.S_IMODE(env_path.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE((directory / ".docker").stat().st_mode))
        self.assertEqual(0o755, stat.S_IMODE(directory.stat().st_mode))
        self.assertEqual(32, len(base64.b64decode(env["PIPELINE_SECRET_KEY"], validate=True)))
        for key in installer.SECRET_KEYS:
            self.assertRegex(env[key], r"^[0-9a-f]{64}$")
        self.assertEqual(len(installer.SECRET_KEYS), len({env[key] for key in installer.SECRET_KEYS}))
        for name in installer.DATA_DIRS:
            self.assertTrue((directory / "data" / name).is_dir())
        for name in installer.PACKAGE_FILES:
            self.assertTrue((directory / name).is_file())
        self.assertEqual(settings(), installer.read_public_settings(directory))

    def test_installations_get_different_credentials(self):
        first = self.install()
        second = self.root / "second"
        installer.install(second, settings())
        self.assertNotEqual((first / ".env").read_bytes(), (second / ".env").read_bytes())

    def test_rerun_preserves_user_configuration_credentials_and_data(self):
        directory = self.install()
        (directory / "config/server.yaml").write_text("user configuration\n")
        (directory / "data/storage/keep").write_bytes(b"stored objects")
        with (directory / ".env").open("a") as output:
            output.write("# User comment\nJWT_SECRET=custom-private-value\n")
        before = snapshot(directory)
        self.assertFalse(installer.install(directory, settings()))
        self.assertFalse(installer.install(directory, None))
        self.assertEqual(before, snapshot(directory))

    def test_changed_settings_or_package_version_do_not_overwrite_installation(self):
        directory = self.install()
        before = snapshot(directory)
        with self.assertRaisesRegex(ValueError, "Existing settings differ"):
            installer.install(directory, settings(GIT_DOMAIN="code.example.com"))
        self.assertEqual(before, snapshot(directory))
        source = self.source_copy()
        (source / "VERSION").write_text("0.2.0\n")
        with self.assertRaisesRegex(ValueError, "different package/version"):
            installer.install(directory, settings(), source)
        self.assertEqual(before, snapshot(directory))

    def test_nonempty_destination_is_preserved(self):
        self.destination.mkdir()
        (self.destination / "existing-data").write_text("keep")
        before = snapshot(self.destination)
        with self.assertRaisesRegex(ValueError, "must be empty"):
            installer.install(self.destination, settings())
        self.assertEqual(before, snapshot(self.destination))

    def test_empty_existing_destination_is_supported(self):
        self.destination.mkdir()
        self.install()

    def test_invalid_settings_fail_before_creating_destination(self):
        invalid = [
            {"BOSCA_DOMAIN": "https://example.com"},
            {"STUDIO_DOMAIN": "example.com; include /tmp/evil;"},
            {"GIT_DOMAIN": "badexample.com"},
            {"GIT_DOMAIN": "studio.example.com"},
            {"CERT_NAME": "../another-certificate"},
            {"API_PORT": "65536"}, {"API_PORT": "0"}, {"API_PORT": "80"},
            {"API_PORT": "443"}, {"API_PORT": "8091"}, {"API_PORT": "１２３"},
        ]
        for overrides in invalid:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                installer.install(self.destination, settings(**overrides))
            self.assertFalse(self.destination.exists())
        with self.assertRaisesRegex(ValueError, "--domain is required"):
            installer.install(self.destination, None)

    def test_incomplete_package_does_not_create_installation(self):
        source = self.source_copy()
        (source / "compose.yaml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or unsupported"):
            installer.install(self.destination, settings(), source)
        self.assertFalse(self.destination.exists())

    def test_render_failure_cleans_up_temporary_installation(self):
        source = self.source_copy()
        (source / "config/nginx.conf.template").write_text("@@UNKNOWN@@")
        with self.assertRaisesRegex(ValueError, "Unresolved placeholders"):
            installer.install(self.destination, settings(), source)
        self.assertFalse(self.destination.exists())
        self.assertEqual([], list(self.root.glob(".bosca-install-*")))

    def test_custom_domains_ports_and_nginx_variables(self):
        directory = self.install(
            STUDIO_DOMAIN="ADMIN.EXAMPLE.COM", GIT_DOMAIN="code.example.com", CERT_NAME="custom-cert",
            API_PORT="18080", GIT_PORT="18091", STUDIO_PORT="13000", BML_PORT="19093",
        )
        nginx = (directory / "config/nginx.conf").read_text()
        bootstrap = (directory / "config/nginx-bootstrap.conf").read_text()
        for host in ("admin.example.com", "code.example.com"):
            self.assertIn(host, nginx)
            self.assertIn(host, bootstrap)
        for port in ("18080", "18091", "13000", "19093"):
            self.assertIn("127.0.0.1:" + port, nginx)
        self.assertIn("/etc/letsencrypt/live/custom-cert/fullchain.pem", nginx)
        self.assertIn("$http_upgrade", nginx)
        self.assertIn("https://$host$request_uri", nginx)
        self.assertNotIn("@@", nginx + bootstrap)

    def test_rerender_uses_public_env_settings_without_changing_credentials(self):
        directory = self.install()
        env_path = directory / ".env"
        env_path.write_text(env_path.read_text().replace("git.example.com", "code.example.com").replace("GIT_PORT=8091", "GIT_PORT=18091"))
        before = env_path.read_bytes()
        installer.render_nginx(directory, installer.read_public_settings(directory))
        nginx = (directory / "config/nginx.conf").read_text()
        self.assertIn("code.example.com", nginx)
        self.assertIn("127.0.0.1:18091", nginx)
        self.assertEqual(before, env_path.read_bytes())

    def test_archive_is_reproducible_and_excludes_installation_state(self):
        source = self.install()
        (source / ".docker/config.json").write_text('{"auths":{"private":"secret"}}')
        (source / "data/storage/private").write_text("private content")
        archive = package.build(self.root / "output", source)
        another = package.build(self.root / "other-output", source)
        self.assertEqual(archive.read_bytes(), another.read_bytes())
        checksum = archive.with_name(archive.name + ".sha256").read_text().split()[0]
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), checksum)
        prefix = "bosca-compose-" + (source / "VERSION").read_text().strip()
        with tarfile.open(archive) as tar:
            self.assertEqual({prefix + "/" + name for name in installer.PACKAGE_FILES}, set(tar.getnames()))
            for item in tar.getmembers():
                self.assertTrue(item.isfile())
                self.assertEqual(0o755 if item.name.endswith(".sh") else 0o644, item.mode)
                self.assertEqual(0, item.uid)
                self.assertEqual(0, item.mtime)

    def test_extracted_archive_installs_from_unrelated_working_directory(self):
        archive = package.build(self.root / "output")
        extracted = self.root / "extracted"
        extracted.mkdir()
        subprocess.run(["tar", "-xzf", str(archive), "-C", str(extracted)], check=True, capture_output=True)
        package_root = next(extracted.iterdir())
        result = subprocess.run(
            ["sh", str(package_root / "install.sh"), "--domain", "example.com", "--directory", str(self.destination)],
            cwd=self.root, text=True, capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Installed Bosca Compose", result.stdout)
        self.assertEqual(settings(), installer.read_public_settings(self.destination))
        self.assertNotIn("JWT_SECRET=", result.stdout + result.stderr)

    def test_cli_rejects_settings_that_would_otherwise_be_ignored(self):
        for arguments in (["--git-port", "18091"], ["--render-nginx", "--cert-name", "bosca"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    ["sh", str(ROOT / "install.sh"), "--directory", str(self.destination), *arguments],
                    text=True, capture_output=True,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.destination.exists())

    @unittest.skipUnless(shutil.which("docker"), "Docker CLI is unavailable")
    def test_compose_resolves_installed_configuration_without_starting_services(self):
        directory = self.install(GIT_PORT="18091", GIT_DOMAIN="code.example.com")
        with (directory / ".env").open("a") as output:
            output.write("BML_MESSAGE_ARTIFACTS_URL=https://artifacts.example.com\n"
                         "BML_MESSAGE_ARTIFACTS_TOKEN=synthetic-test-token\nGIT_VERSION=test-version\n")
        # Docker Desktop keeps Compose in the user's config; Linux installs use
        # a system-wide plugin. Expose the local plugin to this isolated fixture.
        user_plugin = Path.home() / ".docker/cli-plugins/docker-compose"
        if user_plugin.is_file():
            plugins = directory / ".docker/cli-plugins"
            plugins.mkdir()
            (plugins / "docker-compose").symlink_to(user_plugin.resolve())
        # Ignore the developer shell's variables so only this installation supplies them.
        process_env = {key: value for key, value in os.environ.items() if key in ("PATH", "HOME", "DOCKER_CLI_PLUGIN_EXTRA_DIRS")}
        result = subprocess.run(
            ["sh", str(directory / "compose.sh"), "config", "--format", "json"],
            cwd=self.root, env=process_env, text=True, capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        services = json.loads(result.stdout)["services"]
        self.assertEqual({"postgres", "nats", "meilisearch", "server", "git", "studio", "bml-message-server"}, set(services))
        self.assertEqual("https://code.example.com", services["git"]["environment"]["GIT_URL"])
        self.assertEqual("https://studio.example.com", services["server"]["environment"]["APP_URL"])
        self.assertEqual("wss://studio.example.com", services["studio"]["environment"]["NUXT_PUBLIC_WS_URL"])
        self.assertTrue(services["git"]["image"].endswith(":test-version"))
        self.assertTrue(services["server"]["image"].startswith("ghcr.io/bosca-io/bosca/bosca-server:"))
        self.assertEqual("https://artifacts.example.com",
                         services["bml-message-server"]["environment"]["BML_MESSAGE_ARTIFACTS_URL"])
        self.assertEqual("18091", services["git"]["ports"][0]["published"])
        self.assertEqual(2880 * 1024 * 1024, sum(int(service["mem_limit"]) for service in services.values()))
        self.assertEqual('"' + services["server"]["environment"]["NATS_TOKEN"] + '"', services["nats"]["environment"]["NATS_TOKEN"])
        for service in services.values():
            for port in service.get("ports", []):
                self.assertEqual("127.0.0.1", port["host_ip"])
            for volume in service.get("volumes", []):
                self.assertTrue(Path(volume["source"]).exists())


if __name__ == "__main__":
    unittest.main()
