import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

/** Release-owned pins: operators select a harness and supply its provider Secret. */
export function kitImages(version, registry, inspect) {
  const images = {};
  for (const family of ['codex', 'claude']) {
    const repository = `${registry.replace(/\/$/, '')}/kit-${family}-runtime`;
    const image = inspect(`${repository}:${version}`);
    const reference = image.RepoDigests?.find(value => value.startsWith(`${repository}@sha256:`));
    if (!reference || !/^sha256:[a-f0-9]{64}$/.test(image.Id)) throw new Error(`Missing immutable image identity for ${family}`);
    const versions = JSON.parse(image.Config?.Labels?.['io.bosca.kit.versions'] ?? 'null');
    if (!versions || versions.cli !== version || !versions.statePolicy.startsWith(`${family}-`)
      || !versions.adapter || !versions.harness || [versions.transport, versions.acp, versions.archive].some(value => value !== 1)) {
      throw new Error(`Kit image metadata does not match release ${version}: ${family}`);
    }
    images[family] = { image: reference, imageId: image.Id, versions, os: image.Os, architecture: image.Architecture };
  }
  return validateKitImages(images, version);
}

export function validateKitImages(images, version) {
  for (const family of ['codex', 'claude']) {
    const runtime = images[family];
    if (!runtime || !/@sha256:[a-f0-9]{64}$/.test(runtime.image) || !/^sha256:[a-f0-9]{64}$/.test(runtime.imageId)
      || runtime.versions?.cli !== version || !runtime.versions.statePolicy.startsWith(`${family}-`)
      || [runtime.versions.transport, runtime.versions.acp, runtime.versions.archive].some(value => value !== 1)
      || runtime.os !== 'linux' || !['amd64', 'arm64'].includes(runtime.architecture)) throw new Error(`Invalid Kit release metadata: ${family}`);
  }
  return images;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [version, output, input] = process.argv.slice(2);
  if (version === '--verify') validateKitImages(JSON.parse(fs.readFileSync(input, 'utf8')), output);
  else {
    if (!version || !output) throw new Error('Usage: node kit-images.mjs VERSION OUTPUT');
    const images = kitImages(version, process.env.BOSCA_KIT_IMAGE_REGISTRY ?? 'ghcr.io/bosca-io/bosca', reference => {
      const manifest = JSON.parse(execFileSync('docker', ['buildx', 'imagetools', 'inspect', '--raw', reference],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
      const selected = manifest.manifests?.filter(entry => entry.platform?.os === 'linux' && entry.platform?.architecture === 'amd64');
      if (selected && selected.length !== 1) throw new Error('Kit release needs one linux/amd64 manifest');
      const pin = selected ? `${reference.slice(0, reference.lastIndexOf(':'))}@${selected[0].digest}` : reference;
      execFileSync('docker', ['pull', '--platform', 'linux/amd64', pin], { stdio: 'inherit' });
      const image = JSON.parse(execFileSync('docker', ['image', 'inspect', '--format', '{{json .}}', pin], { encoding: 'utf8' }));
      if (selected) {
        if (!image.RepoDigests?.includes(pin)) throw new Error('Pulled Kit image has no matching platform digest');
        image.RepoDigests = [pin];
      }
      return image;
    });
    fs.writeFileSync(output, JSON.stringify(images, null, 2) + '\n');
  }
}
