"""Exercise the rendered Git HTTPRoute with Envoy's RE2 regex engine."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

import re2
import yaml


HELM_ROOT = Path(__file__).resolve().parents[1]
UUID = "8f9b5d41-6c78-4d16-9408-d1ba4af6aff2"
LIBRARY_ASSETS = [
    f"/_bml/libraries/{'a' * 64}/{asset}"
    for asset in ("design.css", "modules/navigation.js", "fonts/geist-latin.woff2")
]


class GitGatewayRoutesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        staging = tempfile.TemporaryDirectory(prefix="bosca-git-routes-")
        cls.addClassCleanup(staging.cleanup)
        for name in ("bosca-common", "bosca-git-server"):
            shutil.copytree(HELM_ROOT / name, Path(staging.name) / name)
        chart = Path(staging.name) / "bosca-git-server"
        subprocess.run(
            ["helm", "dependency", "build", str(chart), "--skip-refresh"],
            check=True, capture_output=True, text=True,
        )
        rendered = subprocess.run(
            [
                "helm", "template", "test", str(chart),
                "--set", "global.domain=example.com",
                "--set", "gateway.hostnames[0]=git.example.com",
                "--show-only", "templates/httproute.yaml",
            ],
            check=True, capture_output=True, text=True,
        ).stdout
        cls.rules = yaml.safe_load(rendered)["spec"]["rules"]

    def port_for(self, path):
        for rule in self.rules:
            for match in rule["matches"]:
                pattern = match["path"]
                if pattern["type"] == "RegularExpression":
                    matches = re2.fullmatch(pattern["value"], path) is not None
                elif pattern["type"] == "PathPrefix":
                    prefix = pattern["value"].rstrip("/")
                    matches = path == prefix or path.startswith(prefix + "/")
                elif pattern["type"] == "Exact":
                    matches = path == pattern["value"]
                else:
                    self.fail(f"Unsupported path type: {pattern['type']}")
                if matches:
                    return "redirect" if "filters" in rule else rule["backendRefs"][0]["port"]
        self.fail(f"No backend for {path}")

    def test_regexes_fit_envoy_default_program_limit(self):
        for rule in self.rules:
            for match in rule["matches"]:
                path = match["path"]
                if path["type"] == "RegularExpression":
                    with self.subTest(pattern=path["value"]):
                        self.assertLessEqual(re2.compile(path["value"]).programsize, 100)

    def test_rules_fit_gateway_api_limits(self):
        self.assertLessEqual(len(self.rules), 16)
        for rule in self.rules:
            self.assertLessEqual(len(rule["matches"]), 8)

    def test_ui_pages_and_assets_redirect_to_atlas(self):
        pages = [
            "login", "welcome", "404", "500", "compare", "search", "statuses",
            "robots.txt", "llms.txt", "sitemap.xml", "favicon.ico", "settings/github",
            "auth/signup", "auth/forgot", "auth/verify", "auth/reset-password",
            "auth/link/confirm", "repositories", "pulls", "pipelines", "ci-agents",
        ]
        for identifier in (UUID, UUID.upper()):
            pages.extend(f"{page}/{identifier}" for page in (
                "repositories", "pulls", "pipelines", "ci-agents",
            ))
            pages.extend(f"repositories/{identifier}/{page}" for page in (
                "settings", "secrets", "github",
            ))
        paths = ["/"] + [f"/{page}{suffix}" for page in pages for suffix in ("", "/")]
        for endpoint in (
            "js", "css", "i18n", "identity", "render", "contract", "action",
            "deferred", "reload", "app.css", "app.js",
        ):
            paths.extend(f"/_bml/{endpoint}{suffix}" for suffix in ("", "/", "/nested/asset.js"))
        for endpoint in ("fonts", "subscriptions"):
            paths.extend(f"/_git/{endpoint}{suffix}" for suffix in ("", "/", "/nested/asset"))
        paths.extend(LIBRARY_ASSETS)
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual("redirect", self.port_for(path))

    def test_library_assets_redirect_to_atlas_in_local_and_compose_proxies(self):
        workspace = HELM_ROOT.parent
        proxies = [
            ("deployments/local/Caddyfile", r'@gitUi path_regexp git_ui "([^"]+)"',
             "redir @gitUi http://{host}:{$BOSCA_ATLAS_PORT:8094}{uri} 302"),
            ("deployments/bosca-compose/config/nginx.conf.template", r'location ~ "([^"]+)"',
             "return 302 https://@@ATLAS_DOMAIN@@$request_uri;"),
        ]
        for file, expression, upstream in proxies:
            config = (workspace / file).read_text()
            match = re.search(expression, config)
            self.assertIsNotNone(match, file)
            self.assertIn(upstream, config[match.end():], file)
            for path in LIBRARY_ASSETS:
                with self.subTest(proxy=file, path=path):
                    self.assertIsNotNone(re2.fullmatch(match.group(1), path))
            for path in ("/_bml/unknown", "/project.git/info/refs", "/graphql"):
                with self.subTest(proxy=file, path=path):
                    self.assertIsNone(re2.fullmatch(match.group(1), path))

    def test_git_transport_and_api_paths_stay_on_api_listener(self):
        paths = [
            "/graphql", "/graphqlws", "/oauth2/google/callback", "/health",
            "/_bml/unknown", "/_git/unknown", "/login/extra", "/unknown",
            f"/repositories/{UUID}/raw/main/README.md",
            f"/repositories/{UUID}/info/refs", f"/pipelines/{UUID}/artifacts",
            "/repositories/not-a-uuid", f"/repositories/{UUID[:-1]}",
            f"/repositories/{UUID}0", f"/repositories/{UUID.replace('-', '')}",
            f"/repositories/{UUID.replace('8', 'z', 1)}",
        ]
        for repository in ("project", "sowers/bosca", "repositories/project", f"repositories/{UUID}"):
            paths.extend(f"/{repository}.git{suffix}" for suffix in (
                "", "/", "/info/refs", "/git-upload-pack", "/git-receive-pack",
                "/info/lfs/objects/batch",
            ))
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(8091, self.port_for(path))


if __name__ == "__main__":
    unittest.main()
