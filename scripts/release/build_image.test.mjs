// Exercise release commands without running Gradle, Docker, or publishing images.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

function fixture(t, overrides = {}) {
  const root = mkdtempSync(join(tmpdir(), 'bosca-release-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'scripts/release'), { recursive: true });
  mkdirSync(join(root, 'bin'));
  const script = join(root, 'scripts/release/build-image.sh');
  copyFileSync(fileURLToPath(new URL('build-image.sh', import.meta.url)), script);
  mkdirSync(join(root, 'ai/kit/runtime/claude'), { recursive: true });
  for (const path of ['compatibility.json', 'claude/compatibility.json']) {
    copyFileSync(fileURLToPath(new URL(`../../ai/kit/runtime/${path}`, import.meta.url)), join(root, 'ai/kit/runtime', path));
  }
  const executable = (path, text) => writeFileSync(join(root, path), text, { mode: 0o755 });
  executable('bin/uname', '#!/bin/bash\ncase "$1" in -s) echo "${TEST_OS:-Linux}";; -m) echo x86_64;; esac\n');
  executable('bin/docker', `#!/bin/bash
printf 'docker %s\n' "$*" >> "$COMMAND_LOG"
if [[ "$*" == *'-f - '* ]]; then cat > "$COPY_STAGE"; fi
if [[ "$1 $2" == 'image inspect' ]]; then
  printf '[{"Id":"sha256:%064d","Os":"linux","Architecture":"amd64","RepoDigests":%s}]\n' 1 "${'${TEST_REPO_DIGESTS:-[]}'}"
fi
`);
  executable('bin/sha256sum', `#!${process.execPath}
const fs = require('node:fs'), crypto = require('node:crypto');
console.log(crypto.createHash('sha256').update(fs.readFileSync(process.argv[2])).digest('hex') + '  ' + process.argv[2]);
`);
  executable('gradlew', `#!/bin/bash
printf 'gradle %s\n' "$*" >> "$COMMAND_LOG"
case "$*" in
  *:server:bosca-server:nativeCompile*)
    mkdir -p server/bosca-server/build/native/nativeCompile
    printf native > server/bosca-server/build/native/nativeCompile/bosca-server ;;
  *:server:bosca-runner:installDist*)
    mkdir -p server/bosca-runner/build/install/bosca-runner
    printf jvm > server/bosca-runner/build/install/bosca-runner/runner ;;
  *:analytics:analytics-collector:nativeCompile*)
    mkdir -p analytics/analytics-collector/build/native/nativeCompile
    printf native > analytics/analytics-collector/build/native/nativeCompile/analytics-collector ;;
  *:cli:nativeCompile*)
    mkdir -p cli/build/native/nativeCompile
    printf native-cli > cli/build/native/nativeCompile/bosca ;;
  *:ai:kit:runtimeImageTest*)
    printf 'gate %s %s %s\n' "$KIT_RUNTIME_IMAGE" "$KIT_RUNTIME_FAMILY" "$KIT_RUNTIME_VERSION" >> "$COMMAND_LOG"
    exit "${'${TEST_GATE_EXIT:-0}'}" ;;
esac
`);
  const environment = { ...process.env, PATH: `${join(root, 'bin')}:${process.env.PATH}`,
    COMMAND_LOG: join(root, 'commands'), COPY_STAGE: join(root, 'copy-stage'),
    PUSH: 'false', BOSCA_IMAGE_REGISTRY: 'example.invalid/bosca', BOSCA_REGISTRY_URL: '', ...overrides };
  return {
    root,
    read: path => readFileSync(join(root, path), 'utf8'),
    run(image) {
      const result = spawnSync('bash', [script, image, 'test'], { env: environment, encoding: 'utf8' });
      assert.ifError(result.error);
      return result;
    },
    build(image) {
      const result = this.run(image);
      assert.equal(result.status, 0, result.stderr);
      return this.read('commands').trim().split('\n');
    }
  };
}

test('native server disables local scripting', t => {
  const f = fixture(t), commands = f.build('bosca-server');
  assert.equal(commands[0], 'gradle --no-daemon --no-configuration-cache -Pbosca.scripting.engine=false :server:bosca-server:nativeCompile');
  assert.equal(f.read('server/artifacts/release-test/bosca-server-native/bosca-server'), 'native');
  assert(commands[1].includes('--build-arg ARTIFACT_SHA=release-test'));
  assert.equal(commands.length, 2);
});

test('JVM runner retains local scripting', t => {
  const f = fixture(t), commands = f.build('bosca-runner');
  assert.equal(commands[0], 'gradle --no-daemon :server:bosca-runner:installDist');
  assert.equal(f.read('server/artifacts/release-test/bosca-runner/runner'), 'jvm');
  assert.equal(commands.length, 2);
});

test('other native images do not receive the server property', t => {
  const commands = fixture(t).build('analytics-collector');
  assert.equal(commands[0], 'gradle --no-daemon --no-configuration-cache :analytics:analytics-collector:nativeCompile');
  assert.equal(commands.length, 2);
});

for (const family of ['codex', 'claude']) {
  test(`${family} runtime uses this checkout's native CLI and passes the image gate before publishing`, t => {
    const image = `kit-${family}-runtime`;
    const digestReference = `example.invalid/bosca/${image}@sha256:${'a'.repeat(64)}`;
    const f = fixture(t, { PUSH: 'true', TEST_REPO_DIGESTS: JSON.stringify([digestReference]) }), commands = f.build(image);
    const sha = createHash('sha256').update('native-cli').digest('hex');
    assert.equal(commands[0], 'gradle --no-daemon --no-configuration-cache :cli:nativeCompile');
    assert.equal(f.read('ai/kit/runtime/artifacts/release-test/bosca-cli-native/bosca'), 'native-cli');
    assert(commands[1].includes(`-t bosca-kit-cli-stage:${sha}`));
    assert(f.read('copy-stage').includes('COPY artifacts/${ARTIFACT_SHA}/bosca-cli-native/bosca /usr/local/bin/bosca'));
    assert(commands[2].includes(`--target ${family} --build-arg BOSCA_CLI_IMAGE=bosca-kit-cli-stage:${sha}`));
    assert(commands[2].includes('--build-arg BOSCA_KIT_VERSIONS='));
    assert(commands[2].includes('"cli":"test"'));
    assert.equal(commands[3], 'gradle --no-daemon :ai:kit:runtimeImageTest');
    assert.equal(commands[4], `gate example.invalid/bosca/${image}:test ${family} test`);
    assert.equal(commands[5], `docker push example.invalid/bosca/${image}:test`);
    assert.equal(commands[6], `docker image inspect example.invalid/bosca/${image}:test`);
    assert.equal(commands.length, 7);
    const manifest = JSON.parse(f.read('ai/kit/runtime/artifacts/release-test/compatibility.json'));
    assert.equal(manifest.image.digestReference, digestReference);
    assert.notEqual(manifest.image.localId, digestReference);
    assert.equal(manifest.cli.sha256, sha);
    assert.equal(manifest.runtime.statePolicy.startsWith(family + '-'), true);
  });
}

test('failed runtime image gate prevents publishing', t => {
  const f = fixture(t, { PUSH: 'true', TEST_GATE_EXIT: '42' });
  assert.equal(f.run('kit-codex-runtime').status, 42);
  assert(!f.read('commands').includes('docker push'));
});

test('PUSH=false performs the runtime gate without publishing', t => {
  const commands = fixture(t).build('kit-claude-runtime');
  assert(commands.some(command => command.startsWith('gate ')));
  assert(!commands.some(command => command.startsWith('docker push ')));
});

test('local runtime compatibility record identifies the exact CLI without inventing a registry digest', t => {
  const f = fixture(t);
  f.build('kit-claude-runtime');
  const manifest = JSON.parse(f.read('ai/kit/runtime/artifacts/release-test/compatibility.json'));
  assert.equal(manifest.image.reference, 'example.invalid/bosca/kit-claude-runtime:test');
  assert.equal(manifest.image.digestReference, null);
  assert.equal(manifest.cli.version, 'test');
  assert.equal(manifest.cli.sha256, createHash('sha256').update('native-cli').digest('hex'));
  assert.equal(manifest.runtime.adapter, '@agentclientprotocol/claude-agent-acp@0.94.0');
  assert.equal(manifest.runtime.harness, '@anthropic-ai/claude-agent-sdk@0.3.303');
  assert.equal(manifest.runtime.acpVersion, 1);
  assert.equal(manifest.runtime.archiveFormatVersion, 1);
});

test('published runtime compatibility record requires the matching repository digest', t => {
  for (const digests of [[], [`other.invalid/kit-codex-runtime@sha256:${'b'.repeat(64)}`]]) {
    const f = fixture(t, { PUSH: 'true', TEST_REPO_DIGESTS: JSON.stringify(digests) });
    const result = f.run('kit-codex-runtime');
    assert.notEqual(result.status, 0);
    assert(result.stderr.includes('no matching registry digest'));
  }
});

test('native runtime release rejects a non-Linux host', t => {
  const result = fixture(t, { TEST_OS: 'Darwin' }).run('kit-codex-runtime');
  assert.notEqual(result.status, 0);
  assert(result.stderr.includes('Linux x86_64'));
});

test('publishing requires an explicit registry', t => {
  const result = fixture(t, { PUSH: 'true', BOSCA_IMAGE_REGISTRY: '' }).run('kit-claude-runtime');
  assert.notEqual(result.status, 0);
  assert(result.stderr.includes('Set BOSCA_REGISTRY_URL or BOSCA_IMAGE_REGISTRY'));
});
