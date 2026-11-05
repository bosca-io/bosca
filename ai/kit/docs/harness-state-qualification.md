# Harness state qualification

Kit restores a native harness conversation by archiving the harness's own files and
reopening them in a fresh process. This document describes that contract and the
native-state requirements it relies on. The probes in `../scripts/qualify-codex-state.py`
and `../scripts/qualify-harness-files.py` exercise it with synthetic data, a separate
home directory for each process, and freshly injected provider authentication. They
do not read the operator's saved configuration or credentials.

## Production operational-directory contract

The native supervisor sets `CODEX_HOME` or `CLAUDE_CONFIG_DIR` to the generation's
owned `harness` directory. It stops all native writers before archiving that
complete tree, the workspace, and persistent scratch files. It excludes the
provider login files `auth.json` (Codex) and `.credentials.json` (Claude), plus
transient Unix sockets in harness/scratch. It does not select database filenames
or reinterpret a vendor's storage schema. Existing archive path, type, checksum,
credential-value, and size checks still apply.

The directory policies are `codex-home-v1` and `claude-home-v1`; they describe
Bosca's directory contract rather than a native package version. A compatible
same-family runtime upgrade retains the saved native session and operational
directory. The new harness opens those files and performs its own supported
migrations. Authority, integration, profile, and context changes still use the
ordinary invalidation and reconstruction rules. Native upgrade or downgrade
compatibility remains the harness's responsibility; Bosca does not convert its
databases or hide a failure to reopen them.

## Codex native state

Codex keeps a session in its `sessions/` transcript files and in SQLite databases
(`state_5.sqlite`, `thread_history_1.sqlite`, `goals_1.sqlite`, `memories_1.sqlite`,
`queue_1.sqlite`). The databases record absolute paths, so the native home and the
workspace are restored at the same mount paths. Restoring the databases at different
paths lets a resume start but fails its first prompt with `thread not found`.

Any `-wal` and `-journal` companions are archived with their databases; a committed
transaction can live only in the WAL ([SQLite WAL](https://www.sqlite.org/wal.html)).
The regenerated shared-memory index (`-shm`), `auth.json`, logs, downloaded plugins,
caches, temporary files and writer locks are excluded. Each archived file has a size
and SHA-256 digest in the manifest, and files are copied without being opened or
rewritten.

An ACP response alone does not mean the native process is idle: Codex can still be
finishing a compaction. The supervisor therefore stops every writer before archiving,
and never retries an uncertain user prompt to rebuild a session.

## Claude native state

`@agentclientprotocol/claude-agent-acp` resumes and loads from local session files.
The SDK's [`SessionStore`](https://code.claude.com/docs/en/agent-sdk/session-storage)
mirror cannot be installed through ACP configuration because it is a live callback
object, and it is marked alpha. Kit does not depend on it.

Claude places transcripts, subagent data and spilled tool results under `projects/`
in `CLAUDE_CONFIG_DIR`, with other session files in additional directories
([directory reference](https://code.claude.com/docs/en/claude-directory)). Recent
attachments and scratch files can live beneath `CLAUDE_CODE_TMPDIR`. Kit archives the
whole configuration directory and persistent scratch for this reason; the provider
login file `.credentials.json` is excluded.

## Shared archive mechanism

Both probes use `harness_state_files.py` to make a gzip-compressed PAX tar archive
containing a versioned JSON manifest and opaque files. The manifest records
harness/version metadata and file sizes and hashes; the archive itself has a
separate checksum. Extraction verifies both before the restored harness reads
the files. Harness-specific code only selects which native files enter the
staging directory. Workspace and persistent scratch files travel alongside them.

The demonstrated common boundary is: stop all writers, copy the selected file
trees, archive, restore at fixed paths, supply current credentials, and use the
harness's native resume/load operation. No common transcript format, vendor
storage callback, or database schema conversion is required by this mechanism.
The probes currently cover regular files; workspace symlinks and complete Git
worktree topology are outside their coverage.

## Boundaries

The mechanism covers clean, between-turn process replacement. ACP
[resume and load](https://agentclientprotocol.com/protocol/v1/session-setup) operate on
state available to the agent; they do not transfer checkpoint files or the workspace
to a new machine, which is why Kit restores files before resuming. Native upgrade and
downgrade compatibility remains the harness's responsibility. The production
dispatcher uses the Kotlin CLI supervisor and the Runner-owned archive lifecycle
described in the [runtime contract](../runtime/README.md); the scripts here are
qualification helpers, not checkpoint exporters.

## Reproduce

Use Python 3.12 or newer and a Node.js version supported by the pinned adapter.
Provide `OPENAI_API_KEY` through the test environment. From the workspace root:

```bash
KIT_STATE_TEST_DIR="$(mktemp -d)"
cat > "$KIT_STATE_TEST_DIR/package.json" <<'JSON'
{
  "private": true,
  "dependencies": {
    "@agentclientprotocol/codex-acp": "2.2.2",
    "@openai/codex": "0.160.1"
  },
  "overrides": { "@openai/codex": "0.160.1" }
}
JSON
npm install --prefix "$KIT_STATE_TEST_DIR" --ignore-scripts --no-audit --no-fund
python3 ai/kit/scripts/qualify-codex-state.py \
  --packages "$KIT_STATE_TEST_DIR/node_modules" \
  --output "$KIT_STATE_TEST_DIR/report.json" \
  --live-provider
```

The opt-in permits real provider calls. An optional `--model` selects a supported
model; the report records the effective model, versions, advertised capabilities,
archive manifest, and each restore result. Temporary homes, authentication, and
workspaces are removed when the probe exits. The JSON report is retained at the
requested path.

The offline SQLite/Claude-reader check makes no provider calls. Install its
pinned adapter in a separate temporary directory and run:

```bash
KIT_CLAUDE_TEST_DIR="$(mktemp -d)"
npm install --prefix "$KIT_CLAUDE_TEST_DIR" --save-exact --ignore-scripts \
  --no-audit --no-fund @agentclientprotocol/claude-agent-acp@0.94.0
python3 -B ai/kit/scripts/qualify-harness-files.py \
  --claude-packages "$KIT_CLAUDE_TEST_DIR/node_modules" \
  --output "$KIT_CLAUDE_TEST_DIR/report.json"
```
