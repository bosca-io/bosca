#!/usr/bin/env python3
"""Exercise a pinned real Codex ACP agent across process and filesystem replacement.

Requires an explicit --live-provider opt-in and OPENAI_API_KEY. Only synthetic test
data is submitted. Dependencies are supplied from an external npm installation;
the probe never reads the operator's Codex configuration or saved credentials.
"""

import argparse
import json
import os
from pathlib import Path
import queue
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

from harness_state_files import archive_files, digest, extract_files


VERSIONS = {"@agentclientprotocol/codex-acp": "2.2.2", "@openai/codex": "0.160.1"}
STATE_DATABASES = ("state_5.sqlite", "thread_history_1.sqlite", "goals_1.sqlite",
                   "memories_1.sqlite", "queue_1.sqlite")


class AcpError(RuntimeError):
    pass


class Acp:
    def __init__(self, root, packages, api_key, model=None):
        self.root = root
        self.home = root / "home"
        self.state = self.home / ".codex"
        self.workspace = root / "workspace"
        self.state.mkdir(parents=True, exist_ok=True)
        self.workspace.mkdir(parents=True, exist_ok=True)
        self.api_key = api_key
        config = {"model_reasoning_effort": "low"}
        if model:
            config["model"] = model
        env = {
            "PATH": os.environ["PATH"], "HOME": str(self.home),
            "CODEX_HOME": str(self.state), "TMPDIR": str(root),
            "NO_BROWSER": "1", "INITIAL_AGENT_MODE": "workspace-write",
            "OPENAI_API_KEY": api_key, "CODEX_CONFIG": json.dumps(config),
        }
        self.process = subprocess.Popen(
            ["node", str(packages / "@agentclientprotocol/codex-acp/dist/index.js")],
            cwd=self.workspace, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, start_new_session=True,
        )
        self.messages = queue.Queue()
        self.sequence = 0
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.messages.put(json.loads(line))
        except Exception as error:
            self.messages.put(error)
        finally:
            self.messages.put(None)

    def _send(self, message):
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()

    def call(self, method, params, timeout=180):
        self.sequence += 1
        request_id = self.sequence
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + timeout
        events = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"ACP {method} timed out")
            try:
                message = self.messages.get(timeout=remaining)
            except queue.Empty as error:
                raise TimeoutError(f"ACP {method} timed out") from error
            if message is None:
                raise AcpError("ACP process closed before its response")
            if isinstance(message, Exception):
                raise message
            if message.get("id") == request_id and "method" not in message:
                if "error" in message:
                    detail = json.dumps(message["error"]).replace(self.api_key, "<redacted>")
                    raise AcpError(detail)
                return message.get("result"), events
            if "id" in message and "method" in message:
                # Local workspace writes need no elevation. Reject host callbacks and
                # any operation that asks for permission beyond the sandbox's grant.
                self._send({"jsonrpc": "2.0", "id": message["id"], "error": {
                    "code": -32601, "message": "Unsupported qualification callback",
                }})
            else:
                events.append(message)

    def initialize(self):
        info, _ = self.call("initialize", {
            "protocolVersion": 1, "clientCapabilities": {},
            "clientInfo": {"name": "bosca-state-qualification", "version": "1"},
        })
        assert info["protocolVersion"] == 1, info
        assert any(method["id"] == "api-key" for method in info["authMethods"])
        self.call("authenticate", {"methodId": "api-key"})
        return info

    def session(self, method="session/new", session_id=None):
        params = {"cwd": str(self.workspace), "mcpServers": []}
        if session_id:
            params["sessionId"] = session_id
        return self.call(method, params)

    def prompt(self, session_id, text):
        result, events = self.call("session/prompt", {
            "sessionId": session_id, "prompt": [{"type": "text", "text": text}],
        })
        assert result["stopReason"] == "end_turn", result
        output = "".join(
            event.get("params", {}).get("update", {}).get("content", {}).get("text", "")
            for event in events
            if event.get("params", {}).get("update", {}).get("sessionUpdate") == "agent_message_chunk"
        )
        return output, events, result

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self._signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._signal(signal.SIGKILL)
                self.process.wait(timeout=5)
        # The adapter can exit before its app-server child. No process in the
        # isolated group may keep a database writer alive during the snapshot.
        self._signal(signal.SIGTERM)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                os.killpg(self.process.pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.05)
        else:
            self._signal(signal.SIGKILL)
            raise RuntimeError("Adapter descendants did not stop cleanly; snapshot rejected")
        self.reader.join(timeout=2)
        self.process.stdout.close()

    def _signal(self, value):
        try:
            os.killpg(self.process.pid, value)
        except ProcessLookupError:
            pass


def tool_events(events):
    return [event for event in events
            if event.get("params", {}).get("update", {}).get("sessionUpdate") == "tool_call"]


def recall(agent, session_id, marker):
    output, events, result = agent.prompt(session_id,
        "What is the conversation-only marker I asked you to remember? "
        "Do not use tools or read files. Reply with exactly the marker, or UNKNOWN if absent.")
    assert output.strip() == marker, f"Conversation marker was not restored: {output[:200]}"
    assert not tool_events(events), "Recall must come from native context, not filesystem reads"
    return result


def snapshot(source, target, api_key):
    """Archive allowlisted state after all writers exit, excluding injected auth.

    This is a qualification helper, not the production checkpoint exporter.
    Treat native state as opaque files. Any retained database WAL or rollback
    journal must travel with its database. No database is opened or rewritten.
    """
    state = source / "home" / ".codex"
    target.mkdir()
    shutil.copytree(state / "sessions", target / "state" / "sessions")
    for name in STATE_DATABASES:
        for suffix in ("", "-wal", "-journal"):
            path = state / (name + suffix)
            if path.exists():
                shutil.copy2(path, target / "state" / path.name)
    shutil.copytree(source / "workspace", target / "workspace")
    return archive_files(target, {"harness": "codex", "versions": VERSIONS,
                                 "snapshotMethod": "files-after-stop"}, [api_key.encode()])


def restore(checkpoint, destination, variant):
    state = destination / "home" / ".codex"
    state.mkdir(parents=True)
    if variant in ("sessions-only", "full-state"):
        shutil.copytree(checkpoint / "state" / "sessions", state / "sessions")
    if variant in ("databases-only", "full-state"):
        for name in STATE_DATABASES:
            for suffix in ("", "-wal", "-journal"):
                path = checkpoint / "state" / (name + suffix)
                if path.exists():
                    shutil.copy2(path, state / path.name)
    shutil.copytree(checkpoint / "workspace", destination / "workspace")
    manifest = json.loads((checkpoint / "manifest.json").read_text())
    for name, info in manifest["files"].items():
        if name.startswith("workspace/"):
            assert digest(destination / name) == info["sha256"], f"Workspace mismatch: {name}"
    assert not (state / "auth.json").exists(), "Credentials must be re-injected, never restored"


def qualify(args):
    packages = args.packages.resolve()
    for package, version in VERSIONS.items():
        actual = json.loads((packages / package / "package.json").read_text())["version"]
        assert actual == version, f"Expected {package}@{version}, found {actual}"
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required; it is never written to the report")
    report = {"versions": VERSIONS, "platform": os.uname().sysname,
              "snapshotMethod": "files-after-stop",
              "provider": "live", "cases": [], "passed": False}
    try:
        with tempfile.TemporaryDirectory(prefix="kit-codex-state-") as directory:
            root = Path(directory).resolve()
            source = root / "source"
            marker = "KIT-MEMORY-" + secrets.token_hex(8)
            artifact = "KIT-WORKSPACE-" + secrets.token_hex(8)
            agent = Acp(source, packages, api_key, args.model)
            try:
                report["capabilities"] = agent.initialize()["agentCapabilities"]
                session, _ = agent.session()
                session_id = session["sessionId"]
                report["model"] = session.get("models", {}).get("currentModelId")
                print("Created isolated native session", flush=True)
                agent.prompt(session_id,
                    f"Remember this conversation-only marker: {marker}. Never write it to a file. "
                    "Do not use tools. Reply exactly READY.")
                recall(agent, session_id, marker)
                print("Warm conversation recall passed", flush=True)
                _, events, _ = agent.prompt(session_id,
                    f"Use your local file tool or shell to create artifact.txt in the current workspace "
                    f"containing exactly {artifact} and a newline. This is an isolated persistence test. "
                    "Do not modify any other files, install anything, or use the network. Reply DONE.")
                assert tool_events(events), "The harness must execute the workspace write"
                assert (agent.workspace / "artifact.txt").read_text() == artifact + "\n"
                # Include binary/untracked files as well as the harness's text output.
                (agent.workspace / "untracked.bin").write_bytes(bytes(range(256)))
                agent.prompt(session_id, "/compact")
                # A terminal response is not a writer-quiescence barrier. Close
                # the session and stop its writers before copying native state;
                # never retry an uncertain prompt to establish that boundary.
                report["compaction"] = "explicit /compact returned; cold recall checked below"
                print("Native tool write and explicit compaction completed", flush=True)
                agent.call("session/close", {"sessionId": session_id})
            finally:
                agent.close()
            checkpoint = root / "checkpoint"
            record_types = {}
            for rollout in (source / "home" / ".codex" / "sessions").rglob("*.jsonl"):
                for line in rollout.read_text().splitlines():
                    entry = json.loads(line)
                    name = entry.get("type", "unknown")
                    record_types[name] = record_types.get(name, 0) + 1
            report["rolloutRecordTypes"] = record_types
            assert record_types.get("compacted", 0) > 0, "Native compacted state was not persisted"
            report["snapshot"] = snapshot(source, checkpoint, api_key)
            # Remove every original path before restoration so stale absolute paths
            # inside databases cannot accidentally make the test pass.
            shutil.rmtree(source)
            assert not source.exists()
            archive = checkpoint.with_suffix(".tar.gz")
            shutil.rmtree(checkpoint)
            extract_files(archive, root, report["snapshot"]["archiveSha256"])
            required_failures = []
            for variant, method, layout, required in [
                    ("empty-state", "session/resume", "relocated", False),
                    ("databases-only", "session/resume", "relocated", False),
                    ("sessions-only", "session/resume", "relocated", True),
                    ("sessions-only", "session/load", "relocated", True),
                    ("full-state", "session/resume", "relocated", False),
                    ("full-state", "session/resume", "preserved", True),
                    ("full-state", "session/load", "preserved", True)]:
                destination = source if layout == "preserved" else root / (variant + "-" + method.split("/")[1])
                assert not destination.exists(), "Original files must be removed before restore"
                restore(checkpoint, destination, variant)
                agent = Acp(destination, packages, api_key, args.model)
                case = {"variant": variant, "method": method, "layout": layout,
                        "required": required, "sessionAccepted": False}
                report["cases"].append(case)
                try:
                    agent.initialize()
                    phase = "open"
                    try:
                        _, history = agent.session(method, session_id)
                        case["sessionAccepted"] = True
                        assert variant != "empty-state", "Session ID alone unexpectedly restored a session"
                        if method == "session/load":
                            assert history, "Load must replay saved history"
                        phase = "recall"
                        recall(agent, session_id, marker)
                        phase = "workspace-read"
                        output, events, _ = agent.prompt(session_id,
                            "Read artifact.txt from the current workspace using a tool. "
                            "Return exactly its contents. Do not write or modify anything.")
                        case["workspaceReadOutput"] = output[:2000]
                        case["workspaceToolEvents"] = [event for event in events if
                            event.get("params", {}).get("update", {}).get("sessionUpdate")
                            in ("tool_call", "tool_call_update")]
                        assert tool_events(events), "Restored workspace must be read through a native tool"
                        # ACP may interleave progress narration with the final answer.
                        # Verify the tool result itself, not just remembered file content.
                        updates = [event["params"]["update"] for event in case["workspaceToolEvents"]]
                        completed = {update["toolCallId"] for update in updates
                                     if update.get("status") == "completed"}
                        tool_output = "".join(update.get("_meta", {}).get("terminal_output_delta", {}).get("data", "")
                                              for update in updates if update["toolCallId"] in completed)
                        assert artifact in tool_output, "Native read did not return restored workspace contents"
                        assert artifact in output, f"Unexpected workspace answer: {output[:200]}"
                        assert (agent.workspace / "artifact.txt").read_text() == artifact + "\n"
                        case.update(restored=True, contextRecalled=True, workspaceRead=True,
                                    historyEvents=len(history), originalFilesRemoved=True)
                        phase = "close"
                        agent.call("session/close", {"sessionId": session_id})
                    except (AcpError, AssertionError) as error:
                        case.update(restored=False, failurePhase=phase, error=str(error)[:1000])
                        if required or variant == "empty-state" and case["sessionAccepted"]:
                            required_failures.append(f"{variant} {layout} {method}: {phase}: {error}")
                    print(f"{variant} {layout} {method}: {'restored' if case['restored'] else 'rejected'}", flush=True)
                finally:
                    agent.close()
                    shutil.rmtree(destination)
            assert not required_failures, "Required restore cases failed: " + "; ".join(required_failures)
            report["passed"] = True
    finally:
        args.output.write_text(json.dumps(report, indent=2).replace(api_key, "<redacted>"))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages", type=Path, required=True, help="Isolated npm node_modules directory")
    parser.add_argument("--output", type=Path, required=True, help="JSON evidence report; contains no API key")
    parser.add_argument("--model", help="Optional model supported by the provider; default is recorded")
    parser.add_argument("--live-provider", action="store_true", help="Allow small real inference calls")
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        parser.error("Python 3.12 or newer is required for filtered archive extraction")
    if not args.live_provider:
        parser.error("This is a live qualification; --live-provider is required")
    qualify(args)
    print("Codex state qualification passed", flush=True)
