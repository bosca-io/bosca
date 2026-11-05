import assert from 'node:assert/strict';
import { test } from 'node:test';
import { kitImages } from './kit-images.mjs';

function image(reference, cli = '6.0.0') {
  const family = reference.includes('kit-codex-') ? 'codex' : 'claude';
  return { RepoDigests: [reference.replace(':6.0.0', '@sha256:' + 'a'.repeat(64))], Id: 'sha256:' + 'b'.repeat(64),
    Os: 'linux', Architecture: 'amd64', Config: { Labels: { 'io.bosca.kit.versions': JSON.stringify({ cli, adapter: 'adapter', harness: 'harness', statePolicy: `${family}-home-v1`, transport: 1, acp: 1, archive: 1 }) } } };
}

test('chart defaults carry immutable identities and exact image-owned versions for both families', () => {
  const catalog = kitImages('6.0.0', 'ghcr.io/bosca-io/bosca', image);
  assert.deepEqual(Object.keys(catalog), ['codex', 'claude']);
  for (const [family, runtime] of Object.entries(catalog)) {
    assert.match(runtime.image, /@sha256:[a-f0-9]{64}$/);
    assert.equal(runtime.versions.cli, '6.0.0');
    assert.equal(runtime.versions.statePolicy, `${family}-home-v1`);
  }
});

test('a mismatched CLI or missing registry identity cannot become published chart defaults', () => {
  assert.throws(() => kitImages('6.0.0', 'registry.invalid', reference => image(reference, '5.0.0')), /does not match release/);
  assert.throws(() => kitImages('6.0.0', 'registry.invalid', reference => ({ ...image(reference), RepoDigests: [] })), /Missing immutable image identity/);
});
