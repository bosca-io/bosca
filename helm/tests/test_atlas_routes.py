"""Validate rendered Atlas routes using the Gateway's RE2 engine and API limits."""
from pathlib import Path
import itertools
import shutil
import subprocess
import tempfile
import unittest
import re2
import yaml

ROOT = Path(__file__).resolve().parents[1]
ID = "382b5ae6-f5f4-47b7-8216-fa76e707e588"

class AtlasRoutesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        stage = tempfile.TemporaryDirectory(prefix="atlas-chart-")
        cls.addClassCleanup(stage.cleanup)
        for chart in ("bosca-common", "bosca-atlas-server"):
            shutil.copytree(ROOT / chart, Path(stage.name) / chart)
        cls.chart = Path(stage.name) / "bosca-atlas-server"
        subprocess.run(["helm", "dependency", "build", str(cls.chart), "--skip-refresh"], check=True, capture_output=True)

    def render(self, *options):
        output = subprocess.run(["helm", "template", "test", str(self.chart),
            "--set", "global.domain=example.com", "--set", "gateway.hostnames[0]=atlas.example.com",
            "--set", "gateway.gitHostnames[0]=git.example.com", "--set", "gateway.registryHostnames[0]=artifacts.example.com",
            *options], check=True, capture_output=True, text=True).stdout
        return [d for d in yaml.safe_load_all(output) if d]

    def routes(self, *options):
        return {d["spec"]["hostnames"][0]: d["spec"]["rules"] for d in self.render(*options) if d["kind"] == "HTTPRoute"}

    def test_all_deployment_combinations_keep_graphql_and_protocols_on_selected_listeners(self):
        for git, artifacts, ui, runner in itertools.product((False, True), repeat=4):
            with self.subTest(git=git, artifacts=artifacts, ui=ui, runner=runner):
                documents = self.render("--set", f"backends.git.serviceName={'git-api' if git else ''}",
                                        "--set", f"backends.artifacts.serviceName={'registry' if artifacts else ''}",
                                        "--set", f"ui.enabled={str(not ui).lower()}",
                                        "--set", f"ui.serviceName={'atlas-ui' if ui else ''}",
                                        "--set", f"runner.enabled={str(runner).lower()}")
                routes = {d["spec"]["hostnames"][0]: d["spec"]["rules"] for d in documents if d["kind"] == "HTTPRoute"}
                atlas = routes["atlas.example.com"]
                self.assertEqual(8094, self.rule(atlas, "/graphql")["backendRefs"][0]["port"])
                self.assertEqual({"name": "atlas-ui" if ui else "test-bosca-atlas-server", "port": 8095},
                                 self.rule(atlas, "/repositories")["backendRefs"][0])
                for host in ("atlas.example.com", "git.example.com"):
                    self.assertEqual(8091 if git else 8094, self.rule(routes[host], "/owner/repo.git/info/refs")["backendRefs"][0]["port"])
                for host in ("atlas.example.com", "artifacts.example.com"):
                    self.assertEqual(8084 if artifacts else 8094, self.rule(routes[host], "/v2/ns/pkg/manifests/latest")["backendRefs"][0]["port"])
                config = yaml.safe_load(next(d["data"]["application.yaml"] for d in documents if d["kind"] == "ConfigMap"))
                self.assertEqual(not ui, config["atlas"]["ui"]["enabled"])
                self.assertEqual(runner, config["atlas"]["runner"]["enabled"])

    def rule(self, rules, path):
        for rule in rules:
            for match in rule["matches"]:
                p = match["path"]
                if (p["type"] == "RegularExpression" and re2.fullmatch(p["value"], path)) or (
                    p["type"] == "PathPrefix" and (path == p["value"] or path.startswith(p["value"].rstrip("/") + "/"))):
                    return rule
        self.fail(path)

    def test_every_page_and_transport_routes_to_the_right_listener(self):
        rules = self.routes()["atlas.example.com"]
        for component in ("git/git-ui", "artifacts/artifacts-ui", "server/atlas-ui"):
            import re
            for source in (ROOT.parent / component / "src/main/bml/pages").glob("*.bml"):
                path = re.search(r'route="([^"]+)"', source.read_text())[1].replace("{id}", ID)
                self.assertEqual(8095, self.rule(rules, path)["backendRefs"][0]["port"], path)
        for path in ("/graphql", "/graphqlws", "/oauth2/google/callback", "/v2/", "/raw/ns/pkg/build/file.zip",
                     "/owner/project.git/git-upload-pack", "/artifacts/project.git/info/refs", f"/repositories/{ID}/raw/main/logo.png"):
            self.assertEqual(8094, self.rule(rules, path)["backendRefs"][0]["port"], path)
        for path in ("/_bml/action", "/_git/subscriptions", "/_artifacts/raw/ns/pkg/build/file.zip", "/favicon.svg",
                     f"/_bml/libraries/{'a' * 64}/styles/design.css",
                     f"/_bml/libraries/{'a' * 64}/chunks/chunk-ABCDEFGH.js",
                     f"/_bml/libraries/{'a' * 64}/fonts/geist-latin.woff2"):
            self.assertEqual(8095, self.rule(rules, path)["backendRefs"][0]["port"], path)

    def test_matchers_fit_gateway_and_envoy_limits(self):
        for rules in self.routes().values():
            self.assertLessEqual(len(rules), 16)
            for rule in rules:
                self.assertLessEqual(len(rule["matches"]), 8)
                for match in rule["matches"]:
                    if match["path"]["type"] == "RegularExpression":
                        self.assertLessEqual(re2.compile(match["path"]["value"]).programsize, 100)

    def test_git_alias_and_registry_rewrites_preserve_canonical_urls(self):
        routes = self.routes()
        git = routes["git.example.com"]
        redirect = self.rule(git, "/repositories")["filters"][0]["requestRedirect"]
        self.assertEqual("atlas.example.com", redirect["hostname"])
        self.assertEqual("atlas.example.com", self.rule(git, f"/_bml/libraries/{'a' * 64}/styles/design.css")["filters"][0]["requestRedirect"]["hostname"])
        self.assertEqual(8094, self.rule(git, "/owner/project.git/info/refs")["backendRefs"][0]["port"])
        registry = routes["artifacts.example.com"]
        for protocol in ("raw", "maven", "npm", "helm", "ml"):
            rule = self.rule(registry, f"/{protocol}/ns/pkg/version/file")
            self.assertEqual(f"/_artifacts/{protocol}", rule["filters"][0]["urlRewrite"]["path"]["replacePrefixMatch"])
        self.assertNotIn("filters", self.rule(registry, "/v2/ns/pkg/manifests/latest"))

    def test_optional_backends_keep_graphql_and_ui_on_atlas(self):
        routes = self.routes("--set", "backends.git.serviceName=git-api", "--set", "backends.artifacts.serviceName=registry")
        atlas = routes["atlas.example.com"]
        self.assertEqual({"name":"git-api", "port":8091}, self.rule(atlas, "/owner/repo.git/info/refs")["backendRefs"][0])
        self.assertEqual({"name":"registry", "port":8084}, self.rule(atlas, "/v2/ns/pkg/manifests/latest")["backendRefs"][0])
        self.assertEqual({"name":"registry", "port":8084}, self.rule(routes["artifacts.example.com"], "/npm/ns/@scope%2Fpkg")["backendRefs"][0])
        self.assertNotIn("filters", self.rule(routes["artifacts.example.com"], "/raw/ns/pkg/ver/file"))
        self.assertEqual({"name":"git-api", "port":8091}, self.rule(routes["git.example.com"], "/owner/repo.git/git-upload-pack")["backendRefs"][0])
        self.assertEqual(8094, self.rule(atlas, "/graphql")["backendRefs"][0]["port"])
        self.assertEqual(8095, self.rule(atlas, "/repositories")["backendRefs"][0]["port"])

    def test_split_ui_and_api_only_routes(self):
        rules = self.routes("--set", "ui.enabled=false", "--set", "ui.serviceName=atlas-ui", "--set", "ui.port=18095")["atlas.example.com"]
        self.assertEqual({"name":"atlas-ui", "port":18095}, self.rule(rules, "/artifacts/namespaces")["backendRefs"][0])
        rules = self.routes("--set", "ui.enabled=false")["atlas.example.com"]
        self.assertEqual(8094, self.rule(rules, "/graphql")["backendRefs"][0]["port"])

if __name__ == "__main__":
    unittest.main()
