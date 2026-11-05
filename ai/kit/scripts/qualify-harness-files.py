#!/usr/bin/env python3
"""Offline file-archive checks with real SQLite and Claude SDK/ACP readers.

Claude transcripts are synthetic fixtures: this does not run Claude inference or
prove complete native session resumption. No provider credential is required.
"""

import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import uuid

from harness_state_files import archive_files, extract_files


VERSIONS = {"@agentclientprotocol/claude-agent-acp": "0.94.0",
            "@anthropic-ai/claude-agent-sdk": "0.3.303"}


def sqlite_files(root):
    source = root / "sqlite-source"
    source.mkdir()
    database = source / "state.sqlite"
    # Leave committed data in the WAL, then exit the sole writer. This exercises
    # a retained journal; a clean SQLite close might checkpoint and remove it.
    writer = """
import os, sqlite3, sys
db=sqlite3.connect(sys.argv[1])
db.execute('pragma journal_mode=wal')
db.execute('pragma wal_autocheckpoint=0')
db.execute('create table proof (value text)')
db.execute("insert into proof values ('committed-in-wal')")
db.commit()
os._exit(0)
"""
    subprocess.run([sys.executable, "-c", writer, str(database)], check=True, timeout=15)
    assert (source / "state.sqlite-wal").stat().st_size > 0
    control = root / "without-wal.sqlite"
    shutil.copy2(database, control)
    with closing(sqlite3.connect(control)) as connection:
        try:
            connection.execute("select value from proof").fetchall()
        except sqlite3.OperationalError as error:
            assert "no such table" in str(error)
        else:
            raise AssertionError("Negative control unexpectedly contained the WAL transaction")
    checkpoint = root / "checkpoint"
    checkpoint.mkdir()
    for name in ("state.sqlite", "state.sqlite-wal"):
        shutil.copy2(source / name, checkpoint / name)
    snapshot = archive_files(checkpoint, {"fixture": "sqlite-retained-wal"})
    shutil.rmtree(source)
    shutil.rmtree(checkpoint)
    restored = extract_files(root / "checkpoint.tar.gz", root, snapshot["archiveSha256"])
    with closing(sqlite3.connect(restored / "state.sqlite")) as connection:
        assert connection.execute("pragma integrity_check").fetchone()[0] == "ok"
        assert connection.execute("select value from proof").fetchall() == [("committed-in-wal",)]
    shutil.rmtree(restored)
    return {"passed": True, "walRequired": True, "originalFilesRemoved": True,
            "snapshot": snapshot}


def read_claude(packages, source, session):
    reader = """
import { pathToFileURL } from 'node:url';
const [packages, session] = process.argv.slice(1);
const sdk = await import(pathToFileURL(packages + '/@anthropic-ai/claude-agent-sdk/sdk.mjs'));
const acp = await import(pathToFileURL(packages + '/@agentclientprotocol/claude-agent-acp/dist/resumed-session.js'));
const messages = await sdk.getSessionMessages(session);
const agents = await sdk.listSubagents(session);
const subagent = await sdk.getSubagentMessages(session, 'fixture-child');
const resumed = await acp.readResumedSession(session);
const tail = await acp.readResumedTail(session);
console.log(JSON.stringify({messages, agents, subagent, resumed, tail}));
"""
    result = subprocess.run(["node", "--input-type=module", "-e", reader, str(packages), session],
        cwd=source / "workspace", env={"PATH": os.environ["PATH"],
            "HOME": str(source / "home"), "CLAUDE_CONFIG_DIR": str(source / "state"),
            "CLAUDE_CODE_TMPDIR": str(source / "scratch")},
        capture_output=True, text=True, check=True, timeout=30)
    return json.loads(result.stdout)


def claude_files(root, packages):
    for package, version in VERSIONS.items():
        assert json.loads((packages / package / "package.json").read_text())["version"] == version
    source = root / "claude-source"
    workspace = source / "workspace"
    workspace.mkdir(parents=True)
    session = str(uuid.uuid4())
    first_id, second_id = str(uuid.uuid4()), str(uuid.uuid4())
    project = "".join(char if char.isalnum() else "-" for char in str(workspace))
    project_dir = source / "state" / "projects" / project
    project_dir.mkdir(parents=True)
    common = {"sessionId": session, "cwd": str(workspace), "version": "fixture",
              "timestamp": "2026-10-22T00:00:00.000Z", "isSidechain": False}
    entries = [
        {**common, "type": "user", "uuid": first_id, "parentUuid": None,
         "origin": {"kind": "human"}, "permissionMode": "default",
         "message": {"role": "user", "content": "Remember CLAUDE-FILE-FIXTURE"}},
        {**common, "type": "assistant", "uuid": second_id, "parentUuid": first_id,
         "message": {"id": "fixture-message", "type": "message", "role": "assistant",
                     "model": "qualification-fixture-model", "stop_reason": "end_turn",
                     "content": [{"type": "text", "text": "CLAUDE-FILE-FIXTURE"}]}},
    ]
    (project_dir / (session + ".jsonl")).write_text("\n".join(json.dumps(e) for e in entries) + "\n")
    child = project_dir / session / "subagents" / "agent-fixture-child.jsonl"
    child.parent.mkdir(parents=True)
    child.write_text("\n".join(json.dumps({**e, "isSidechain": True, "agentId": "fixture-child"})
                               for e in entries) + "\n")
    sidecars = {
        f"state/projects/{project}/{session}/tool-results/result.txt": b"tool output fixture",
        f"state/file-history/{session}/original": b"pre-edit fixture",
        "state/plans/fixture.md": b"plan fixture",
        f"state/tasks/{session}/1.json": b'{"id":"1","status":"pending"}',
        "state/agent-memory/fixture/MEMORY.md": b"native memory fixture",
        "scratch/attachment.bin": bytes(range(256)),
        "workspace/untracked.txt": b"workspace fixture",
    }
    for name, value in sidecars.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
    excluded = b"SYNTHETIC-AUTH-MUST-NOT-BE-ARCHIVED"
    (source / "state" / ".credentials.json").write_bytes(excluded)
    before = read_claude(packages, source, session)
    assert len(before["messages"]) == 2 and len(before["subagent"]) == 2
    assert before["agents"] == ["fixture-child"]
    assert before["resumed"]["messages"] == before["messages"]
    assert before["tail"] == {"model": "qualification-fixture-model", "permissionMode": "default"}
    checkpoint = root / "checkpoint"
    for directory in ("projects", "file-history", "plans", "tasks", "agent-memory"):
        shutil.copytree(source / "state" / directory, checkpoint / "state" / directory)
    for directory in ("workspace", "scratch"):
        shutil.copytree(source / directory, checkpoint / directory)
    snapshot = archive_files(checkpoint, {"harness": "claude", "versions": VERSIONS,
                                         "fixture": "synthetic-transcript"}, [excluded])
    shutil.rmtree(source)
    shutil.rmtree(checkpoint)
    restored = extract_files(root / "checkpoint.tar.gz", root, snapshot["archiveSha256"])
    restored.rename(source)
    after = read_claude(packages, source, session)
    assert after == before, "Native SDK/ACP readers differ after file restore"
    for name, value in sidecars.items():
        assert (source / name).read_bytes() == value
    assert not (source / "state" / ".credentials.json").exists()
    return {"passed": True, "provider": "none", "fixture": "synthetic-transcript",
            "mainMessages": len(after["messages"]), "subagentMessages": len(after["subagent"]),
            "acpResumeReaders": True, "originalFilesRemoved": True, "snapshot": snapshot}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--claude-packages", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        parser.error("Python 3.12 or newer is required for filtered archive extraction")
    report = {"passed": False, "provider": "none", "snapshotMethod": "files-after-stop"}
    try:
        with tempfile.TemporaryDirectory(prefix="kit-harness-files-") as directory:
            root = Path(directory).resolve()
            report["sqlite"] = sqlite_files(root)
            print("SQLite database plus retained WAL restore passed", flush=True)
            report["claude"] = claude_files(root, args.claude_packages.resolve())
            print("Claude SDK/ACP file readers and sidecar restore passed (synthetic fixture)", flush=True)
            report["passed"] = True
    finally:
        args.output.write_text(json.dumps(report, indent=2))
