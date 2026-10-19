"""Build production Dockerfiles and exercise their native PostgreSQL clients.

Run from the workspace root:
    python3 -m unittest discover -s server/tests -p postgres_runtime_images_test.py -v

Requires Docker with network access. Application artifacts are isolated shell fixtures;
this checks runtime packaging, not a built Bosca native executable. Images, databases,
networks and scratch files belong to this test and are removed afterward.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid


class PostgresRuntimeImagesTest(unittest.TestCase):
    platform = os.environ.get("BOSCA_TEST_PLATFORM", "linux/amd64")
    server = Path(__file__).resolve().parents[1]

    @classmethod
    def docker(cls, *arguments, **kwargs):
        return subprocess.run(
            ["docker", *arguments], check=True, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1200, **kwargs,
        )

    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="bosca-postgres-images-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.context = Path(cls.temporary.name)
        cls.identity = uuid.uuid4().hex
        cls.network = f"bosca-pg-runtime-{cls.identity}"
        cls.database = f"bosca-pg-runtime-db-{cls.identity}"
        cls.images = []
        cls.addClassCleanup(cls.cleanup_docker)
        cls.docker("network", "create", cls.network)
        cls.docker(
            "run", "--detach", "--platform", cls.platform, "--name", cls.database,
            "--network", cls.network, "--env", "POSTGRES_PASSWORD=runtime-test-only",
            "--env", "POSTGRES_DB=source", "pgvector/pgvector:pg18",
        )
        deadline = time.monotonic() + 90
        while True:
            try:
                cls.sql("select 1")
                break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        cls.sql("""
            create extension vector;
            create schema content;
            create table content.assets(body jsonb, embedding vector(3), permissions text[]);
            insert into content.assets values ('{"name":"captured"}', '[1,2,3]', array['VIEW','EDIT']);
            create role recovered_owner superuser login password 'runtime-test-only';
        """)
        for staged, binary in [
            ("bosca-server-native", "bosca-server"),
            ("bosca-server", "bin/bosca-server"),
            ("bosca-runner", "bin/bosca-runner"),
        ]:
            artifact = cls.context / "artifacts/test" / staged / binary
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_text("#!/bin/sh\nprintf '%s\\n' 'runtime fixture'\n")
            artifact.chmod(0o755)
        scripts = cls.server / "scripts"
        if scripts.exists():
            shutil.copytree(scripts, cls.context / "scripts")

    @classmethod
    def cleanup_docker(cls):
        # Only named resources created by this invocation are eligible for cleanup.
        for arguments in [
            ("rm", "--force", cls.database),
            ("network", "rm", cls.network),
            *[("image", "rm", "--force", image) for image in cls.images],
        ]:
            subprocess.run(["docker", *arguments], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)

    @classmethod
    def sql(cls, query, database="source"):
        return cls.docker(
            "exec", "--interactive", cls.database, "psql", "--no-psqlrc",
            "--set=ON_ERROR_STOP=1", "--username=postgres", f"--dbname={database}", input=query,
        )

    def client(self, image, tool, *arguments):
        return self.docker(
            "run", "--rm", "--platform", self.platform, "--network", self.network,
            "--env", "PGSERVICEFILE=/work/services.conf", "--volume", f"{self.context}:/work",
            "--entrypoint", tool, image, *arguments,
        )

    def exercise(self, dockerfile, suffix, entrypoint, jvm=False):
        image = f"bosca-postgres-runtime-test:{self.identity}-{suffix}"
        self.images.append(image)
        destination = self.context / dockerfile
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.server / dockerfile, destination)
        try:
            self.docker(
                "build", "--platform", self.platform, "--file", str(destination),
                "--build-arg", "ARTIFACT_SHA=test", "--tag", image, str(self.context),
            )
            actual = json.loads(self.docker("image", "inspect", "--format", "{{json .Config.Entrypoint}}", image).stdout)
            self.assertEqual(entrypoint, actual)
            self.assertEqual("runtime fixture", self.docker("run", "--rm", image).stdout.strip())
            for tool in ["pg_dump", "pg_restore", "psql"]:
                version = self.client(image, tool, "--version").stdout.strip()
                print(f"{suffix}: {version}", flush=True)
                self.assertRegex(version, r"\(PostgreSQL\) 18\.")
            self.client(image, "sh", "-ec", "test ! -e /usr/lib/postgresql/18/bin/postgres; test -s /etc/ssl/certs/ca-certificates.crt")
            if jvm:
                self.assertIn("openjdk", self.client(image, "java", "-version").stderr.lower())
                self.assertIn("ffmpeg version", self.client(image, "ffmpeg", "-version").stdout)
            target = f"recovered_{suffix}"
            self.sql(f"create database {target} owner recovered_owner template template0", "postgres")
            self.sql("update content.assets set body='{\"name\":\"captured\"}'")
            (self.context / "services.conf").write_text(
                f"[source]\nhost={self.database}\nport=5432\ndbname=source\nuser=postgres\npassword=runtime-test-only\n"
                f"[target]\nhost={self.database}\nport=5432\ndbname={target}\nuser=recovered_owner\npassword=runtime-test-only\n"
            )
            dump = f"/work/{suffix}.dump"
            self.client(image, "pg_dump", "--no-password", "--format=custom", "--dbname=service=source", f"--file={dump}")
            self.sql("update content.assets set body='{\"name\":\"after-dump\"}'")
            self.client(
                image, "pg_restore", "--no-password", "--dbname=service=target", "--single-transaction",
                "--exit-on-error", "--no-owner", "--no-acl", "--no-tablespaces", dump,
            )
            result = self.client(
                image, "psql", "--no-psqlrc", "--no-password", "--set=ON_ERROR_STOP=1",
                "--dbname=service=target", "--tuples-only", "--no-align",
                "--command=select body->>'name', embedding::text, permissions::text from content.assets; "
                "select tableowner from pg_tables where schemaname='content' and tablename='assets';",
            ).stdout.strip()
            self.assertEqual("captured|[1,2,3]|{VIEW,EDIT}\nrecovered_owner", result)
            source = self.client(
                image, "psql", "--no-psqlrc", "--no-password", "--dbname=service=source",
                "--tuples-only", "--no-align", "--command=select body->>'name' from content.assets",
            ).stdout.strip()
            self.assertEqual("after-dump", source)
        except subprocess.CalledProcessError as error:
            self.fail(f"{suffix}: command {error.cmd!r} exited {error.returncode}\n{error.stdout}\n{error.stderr}")

    def test_native_server_clients_dump_and_restore(self):
        self.exercise("bosca-server/Dockerfile.graalvm", "native", ["./bosca-server"])

    def test_jvm_server_clients_dump_and_restore(self):
        self.exercise("bosca-server/Dockerfile", "server", ["./bin/bosca-server"], jvm=True)

    def test_runner_clients_dump_and_restore(self):
        self.exercise("bosca-runner/Dockerfile", "runner", ["./bin/bosca-runner"], jvm=True)


if __name__ == "__main__":
    unittest.main()
