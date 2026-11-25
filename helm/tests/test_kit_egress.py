"""Render the umbrella chart's Kit egress proxy and Kit network policies."""

from pathlib import Path
import json
import shutil
import subprocess
import tempfile
import unittest

import yaml


HELM_ROOT = Path(__file__).resolve().parents[1]
RUNTIME = {
    "image": "ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:" + "a" * 64,
    "imageId": "sha256:" + "b" * 64,
    "versions": {"cli": "6.40.0", "adapter": "fixture", "harness": "fixture", "statePolicy": "codex-home-v1",
                 "transport": 1, "acp": 1, "archive": 1},
}
PRIVATE = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "127.0.0.0/8"]


def run(*arguments):
    result = subprocess.run(arguments, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(f"{' '.join(map(str, arguments))}\n{result.stdout}\n{result.stderr}")
    return result.stdout


class KitEgressTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        staging = tempfile.TemporaryDirectory(prefix="bosca-kit-egress-")
        cls.addClassCleanup(staging.cleanup)
        root = Path(staging.name)
        for metadata in HELM_ROOT.glob("*/Chart.yaml"):
            if metadata.parent.name != "bosca-infra":
                shutil.copytree(metadata.parent, root / metadata.parent.name, ignore=shutil.ignore_patterns("charts"))
        # Subcharts carry their own bosca-common copy, so build them before the umbrella packages them.
        for chart in sorted(root.iterdir()):
            if chart.name != "bosca-services" and yaml.safe_load((chart / "Chart.yaml").read_text()).get("dependencies"):
                run("helm", "dependency", "build", str(chart), "--skip-refresh")
        cls.chart = root / "bosca-services"
        run("helm", "dependency", "build", str(cls.chart), "--skip-refresh")

    def render(self, *settings, kit=True, harness="codex"):
        arguments = ["helm", "template", "test", str(self.chart), "--namespace", "bosca", "--set", "global.domain=example.com"]
        if kit:
            arguments += ["--set", "global.kit.enabled=true", "--set", f"global.kit.harness={harness}",
                          "--set-json", "global.kit.runtime=" + json.dumps({**RUNTIME, "versions": {
                              **RUNTIME["versions"], "statePolicy": f"{harness}-home-v1"}})]
        return [document for document in yaml.safe_load_all(run(*arguments, *settings)) if document]

    @staticmethod
    def find(documents, kind, name):
        matches = [d for d in documents if d["kind"] == kind and d["metadata"]["name"] == name]
        return matches[0] if len(matches) == 1 else None

    @staticmethod
    def profiles(documents):
        return [yaml.safe_load(d["data"]["application.yaml"])["kit"]["harness"]["profiles"][0] for d in documents
                if d["kind"] == "ConfigMap" and "kit:" in d.get("data", {}).get("application.yaml", "")]

    def routed(self):
        return self.render(
            "--set", "bosca-server.gateway.hostnames={example.com,api.example.com}",
            "--set", "bosca-studio.gateway.hostnames={studio.example.com}",
            "--set", "bosca-git-server.enabled=true", "--set", "bosca-git-server.gateway.hostnames={git.example.com}",
            "--set", "gateway.extraHostnames={extra.example.com}",
        )

    def test_enabled_kit_renders_proxy_and_both_policies(self):
        documents = self.routed()
        deployment = self.find(documents, "Deployment", "test-kit-egress")
        service = self.find(documents, "Service", "test-kit-egress")
        self.assertIsNotNone(deployment)
        self.assertIsNotNone(service)
        self.assertIsNotNone(self.find(documents, "NetworkPolicy", "test-kit-runtime"))
        self.assertIsNotNone(self.find(documents, "NetworkPolicy", "test-kit-egress"))
        self.assertEqual([{"name": "proxy", "port": 3128, "targetPort": "proxy", "protocol": "TCP"}], service["spec"]["ports"])
        self.assertEqual(deployment["spec"]["selector"]["matchLabels"], service["spec"]["selector"])
        self.assertEqual(1, deployment["spec"]["replicas"])
        pod = deployment["spec"]["template"]["spec"]
        self.assertFalse(pod["automountServiceAccountToken"])
        self.assertEqual([{"name": "docker-registry"}], pod["imagePullSecrets"])
        self.assertTrue(pod["securityContext"]["runAsNonRoot"])
        self.assertEqual({"type": "RuntimeDefault"}, pod["securityContext"]["seccompProfile"])
        container = pod["containers"][0]
        self.assertEqual("ghcr.io/bosca-io/bosca/bosca-cli:6.40.0", container["image"])
        self.assertEqual({"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True, "runAsNonRoot": True,
                          "runAsUser": 10001, "runAsGroup": 10001, "capabilities": {"drop": ["ALL"]}}, container["securityContext"])
        self.assertIn("limits", container["resources"])
        self.assertEqual(["harness", "egress", "--port", "3128", "--bosca-url", "http://test-bosca-server.bosca.svc:8080"],
                         container["args"][:6])

    def test_proxy_allows_every_gateway_hostname(self):
        documents = self.routed()
        gateway = self.find(documents, "Gateway", "bosca-gateway")
        listeners = [listener["hostname"] for listener in gateway["spec"]["listeners"] if "hostname" in listener]
        arguments = self.find(documents, "Deployment", "test-kit-egress")["spec"]["template"]["spec"]["containers"][0]["args"][6:]
        self.assertEqual(["--allow"] * len(listeners), arguments[0::2])
        self.assertEqual([f"{host}:443" for host in listeners], arguments[1::2])
        self.assertEqual({"example.com", "api.example.com", "studio.example.com", "git.example.com", "extra.example.com"}, set(listeners))

    def test_profile_points_runtimes_at_the_proxy_with_the_family_provider(self):
        for harness, host in (("codex", "api.openai.com:443"), ("claude", "api.anthropic.com:443")):
            with self.subTest(harness=harness):
                profiles = self.profiles(self.render(harness=harness))
                self.assertEqual(2, len(profiles))
                for profile in profiles:
                    self.assertEqual("http://test-kit-egress.bosca.svc:3128", profile["egressProxy"])
                    self.assertEqual([host], profile["egressHosts"])

    def test_profile_overrides_still_apply(self):
        documents = self.render("--set", "global.kit.profile.egressHosts={api.example.net:443}", "--set", "global.kit.profile.idleSeconds=60")
        for profile in self.profiles(documents):
            self.assertEqual(["api.example.net:443"], profile["egressHosts"])
            self.assertEqual("http://test-kit-egress.bosca.svc:3128", profile["egressProxy"])
            self.assertEqual(60, profile["idleSeconds"])

    def test_runtime_policy_selects_kit_pods_and_limits_their_peers(self):
        for harness in ("codex", "claude"):
            with self.subTest(harness=harness):
                policy = self.find(self.render(harness=harness), "NetworkPolicy", "test-kit-runtime")["spec"]
                runtime = {"app.kubernetes.io/managed-by": "bosca-kubernetes-jobs", "kubernetes.bosca.io/job-profile": f"kit-{harness}"}
                self.assertEqual(runtime, policy["podSelector"]["matchLabels"])
                self.assertEqual(["Ingress", "Egress"], policy["policyTypes"])
                self.assertEqual([{"from": [{"podSelector": {"matchLabels": {"app.kubernetes.io/name": "bosca-runner", "app.kubernetes.io/instance": "test"}}}],
                                   "ports": [{"protocol": "TCP", "port": 8080}]}], policy["ingress"])
                dns, server, proxy = policy["egress"]
                self.assertEqual([{"namespaceSelector": {}, "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}], dns["to"])
                self.assertEqual([{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}], dns["ports"])
                self.assertEqual({"app.kubernetes.io/name": "bosca-server", "app.kubernetes.io/instance": "test"}, server["to"][0]["podSelector"]["matchLabels"])
                self.assertEqual([{"protocol": "TCP", "port": 8080}], server["ports"])
                self.assertEqual({"app.kubernetes.io/name": "kit-egress", "app.kubernetes.io/instance": "test"}, proxy["to"][0]["podSelector"]["matchLabels"])
                self.assertEqual([{"protocol": "TCP", "port": 3128}], proxy["ports"])
                self.assertFalse(any("ipBlock" in peer for rule in policy["egress"] for peer in rule["to"]))

    def test_proxy_policy_admits_runtimes_and_reaches_public_addresses_and_routed_pods(self):
        documents = self.routed()
        policy = self.find(documents, "NetworkPolicy", "test-kit-egress")["spec"]
        proxy = self.find(documents, "Deployment", "test-kit-egress")["spec"]["template"]["metadata"]["labels"]
        self.assertEqual(proxy, policy["podSelector"]["matchLabels"])
        self.assertEqual([{"from": [{"podSelector": {"matchLabels": {"app.kubernetes.io/managed-by": "bosca-kubernetes-jobs",
                                                                        "kubernetes.bosca.io/job-profile": "kit-codex"}}}],
                           "ports": [{"protocol": "TCP", "port": 3128}]}], policy["ingress"])
        pods = {rule["to"][0]["podSelector"]["matchLabels"].get("app.kubernetes.io/name"): [p["port"] for p in rule["ports"]]
                for rule in policy["egress"] if "podSelector" in rule["to"][0] and "namespaceSelector" not in rule["to"][0]}
        self.assertEqual({"bosca-server": [8080], "bosca-collector": ["http"], "bosca-studio": ["http"],
                          "bosca-git-server": ["http", "ui"]}, pods)
        blocks = [peer["ipBlock"] for rule in policy["egress"] for peer in rule["to"] if "ipBlock" in peer]
        self.assertEqual([{"cidr": "0.0.0.0/0", "except": PRIVATE}], blocks)

    def test_registry_pull_secret_image_and_replica_overrides_apply_to_the_proxy(self):
        documents = self.render("--set", "global.imageRegistry=registry.example.invalid/bosca",
                                "--set-json", "bosca-kubernetes-controller.imagePullSecrets=[]")
        pod = self.find(documents, "Deployment", "test-kit-egress")["spec"]["template"]["spec"]
        self.assertEqual("registry.example.invalid/bosca/bosca-cli:6.40.0", pod["containers"][0]["image"])
        self.assertNotIn("imagePullSecrets", pod)
        pod = self.find(self.render("--set", "global.kit.egress.image.tag=custom", "--set", "global.kit.egress.replicas=2"),
                        "Deployment", "test-kit-egress")
        self.assertEqual(2, pod["spec"]["replicas"])
        self.assertEqual("ghcr.io/bosca-io/bosca/bosca-cli:custom", pod["spec"]["template"]["spec"]["containers"][0]["image"])

    def test_nothing_renders_when_kit_or_egress_is_disabled(self):
        for documents in (self.render(kit=False), self.render("--set", "global.kit.egress.enabled=false")):
            names = {(d["kind"], d["metadata"]["name"]) for d in documents}
            self.assertFalse({("Deployment", "test-kit-egress"), ("Service", "test-kit-egress"),
                              ("NetworkPolicy", "test-kit-runtime"), ("NetworkPolicy", "test-kit-egress")} & names)
            self.assertFalse(any(d["kind"] == "NetworkPolicy" for d in documents))
            for profile in self.profiles(documents):
                self.assertNotIn("egressProxy", profile)
                self.assertNotIn("egressHosts", profile)


if __name__ == "__main__":
    unittest.main()
