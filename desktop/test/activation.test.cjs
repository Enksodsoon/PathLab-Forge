const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { activate, inventory, digest } = require('../src/activation.cjs');
test('pinned packaged policy rejects changed JAR/JVM/reader bytes and manifest downgrade before execution', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-activation-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const key = crypto.generateKeyPairSync('ed25519').publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const manifest = { version: '1.0.0-rc.1', platform: 'win32', arch: 'x64', distribution: 'PRODUCTION', internalValidation: false, requireProductionRuntime: true, featureCatalogPublicKey: key };
  fs.writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify(manifest));
  for (const file of ['app.jar', 'java.exe', 'codec.dll']) fs.writeFileSync(path.join(root, file), 'synthetic bytes');
  const policy = { schema: 'pathlab.forge.activation-policy/1', version: '1.0.0-rc.1', platform: 'win32', arch: 'x64', distribution: 'PRODUCTION', commit: 'a'.repeat(40), sourceDirty: false, manifest, files: inventory(root) };
  assert.equal(activate(root, policy, 'win32', 'x64', policy.version), manifest);
  assert.throws(() => activate(root, undefined, 'win32', 'x64', policy.version));
  assert.throws(() => activate(root, policy, 'win32', 'arm64', policy.version));
  assert.throws(() => activate(root, policy, 'win32', 'x64', '1.0.0'));
  for (const file of ['app.jar', 'java.exe', 'codec.dll']) {
    fs.appendFileSync(path.join(root, file), 'tampered');
    assert.throws(() => activate(root, policy, 'win32', 'x64', policy.version), /integrity/);
    fs.writeFileSync(path.join(root, file), 'synthetic bytes');
  }
  fs.writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify({ ...manifest, distribution: 'INTERNAL_NON_REDISTRIBUTABLE', internalValidation: true, requireProductionRuntime: false }));
  assert.throws(() => activate(root, policy, 'win32', 'x64', policy.version), /integrity/);
  assert.equal(digest(path.join(root, 'app.jar')), crypto.createHash('sha256').update('synthetic bytes').digest('hex'));
});
