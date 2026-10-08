const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { inventory, exactInventory, preflight, signing, publicKey, validateReview, sha256 } = require('../release.cjs');
const { validateCatalog } = require('../../scripts/distribution.cjs');
test('inventory detects tamper and preflight rejects wrong target/ambiguous distribution', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-release-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, 'runtime/bin'), { recursive: true });
  fs.mkdirSync(path.join(root, 'lib'));
  fs.writeFileSync(path.join(root, 'runtime/bin/java.exe'), 'synthetic');
  fs.writeFileSync(path.join(root, 'lib/app.jar'), 'synthetic');
  const manifest = { platform: 'win32', arch: 'x64', distribution: 'INTERNAL_NON_REDISTRIBUTABLE', internalValidation: true, requireProductionRuntime: false };
  fs.writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify(manifest));
  assert.equal(preflight(root, 'win32', 'x64', {}), false);
  assert.throws(() => preflight(root, 'win32', 'arm64', {}));
  assert.throws(() => preflight(root, 'darwin', 'x64', {}));
  const before = inventory(root);
  exactInventory(root, before);
  fs.appendFileSync(path.join(root, 'lib/app.jar'), 'tampered');
  assert.throws(() => exactInventory(root, before), /changed/);
  fs.writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify({ ...manifest, internalValidation: false }));
  assert.throws(() => preflight(root, 'win32', 'x64', {}), /channel/);
});
test('signing uses store identity/keychain with required timestamp and no passwords', () => {
  assert.throws(() => signing('win32', {}));
  assert.throws(() => signing('darwin', {}));
  const options = signing('win32', { PATHLAB_FORGE_WINDOWS_CERT_SHA1: 'a'.repeat(40), PATHLAB_FORGE_TIMESTAMP_URL: 'https://timestamp.example.com' });
  assert.match(options.windowsSign.signWithParams, /\/fd SHA256.*\/tr https:.*\/td SHA256/);
  assert.doesNotMatch(JSON.stringify(options), /password|certificateFile/);
  assert.throws(() => signing('win32', { PATHLAB_FORGE_WINDOWS_CERT_SHA1: 'a'.repeat(40), PATHLAB_FORGE_TIMESTAMP_URL: 'http://timestamp.example.com' }));
  assert.equal(signing('darwin', { PATHLAB_FORGE_MAC_IDENTITY: 'Developer ID Application: Example (ABCDEFGHIJ)', PATHLAB_FORGE_NOTARY_PROFILE: 'trusted-profile' }).osxNotarize.keychainProfile, 'trusted-profile');
  assert.equal(signing('darwin', { PATHLAB_FORGE_MAC_IDENTITY: 'Developer ID Application: Example (ABCDEFGHIJ)', PATHLAB_FORGE_NOTARY_PROFILE: 'trusted-profile' }).osxSign.continueOnError, false);
});
test('feature catalog public key rejects missing and wrong algorithms', () => {
  assert.throws(() => publicKey('PENDING_REVIEW'));
  for (const algorithm of ['ed25519', 'x25519']) {
    const value = crypto.generateKeyPairSync(algorithm).publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
    if (algorithm === 'ed25519') assert.equal(publicKey(value), value);
    else assert.throws(() => publicKey(value));
  }
});
test('review and catalog fail closed on incomplete or mismatched exact evidence', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-catalog-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const commit = 'a'.repeat(40);
  fs.writeFileSync(path.join(directory, 'source.tar'), 'synthetic');
  fs.writeFileSync(path.join(directory, 'installer.exe'), 'synthetic installer');
  const sourceHash = sha256(path.join(directory, 'source.tar'));
  fs.writeFileSync(path.join(directory, 'java-dependencies.json'), '[]');
  fs.writeFileSync(path.join(directory, 'npm-dependencies.json'), '[]');
  const receipt = { schema: 'pathlab.forge.inventory/1', commit, target: 'win32-x64', version: '1.0.0-rc.1', source: { file: 'source.tar', sha256: sourceHash }, files: [{ path: 'app.jar', sha256: 'b'.repeat(64) }],
    javaDependenciesSha256: sha256(path.join(directory, 'java-dependencies.json')), npmDependenciesSha256: sha256(path.join(directory, 'npm-dependencies.json')) };
  fs.writeFileSync(path.join(directory, 'inventory.json'), JSON.stringify(receipt));
  const reviewed = { file: 'source.tar', sha256: sourceHash };
  const review = { schema: 'pathlab.forge.distribution-review/1', commit, target: receipt.target, inventorySha256: sha256(path.join(directory, 'inventory.json')),
    sourceSha256: sourceHash, decision: 'APPROVED', reviewer: 'synthetic test reviewer', applicationLicense: 'GPL-3.0-or-later', notices: reviewed, correspondingSource: reviewed,
    buildInstructions: reviewed, licenseText: reviewed, components: [{ sha256: 'b'.repeat(64), decision: 'APPROVED', license: 'MIT' }] };
  validateReview(review, receipt, directory);
  assert.throws(() => validateReview({ ...review, components: [] }, receipt, directory));
  assert.throws(() => validateReview({ ...review, commit: 'c'.repeat(40) }, receipt, directory));
  fs.writeFileSync(path.join(directory, 'review.json'), JSON.stringify(review));
  const artifact = { file: 'installer.exe', sha256: sha256(path.join(directory, 'installer.exe')), bytes: fs.statSync(path.join(directory, 'installer.exe')).size };
  const evidence = { commit, target: receipt.target, version: receipt.version, artifactSha256: artifact.sha256, result: 'PASS', platforms: ['Windows 10 22H2', 'Windows 11'],
    dataPreserved: true, upgradeRollback: true, accessibility: true, journeys: true, timestampVerified: true, nestedVerified: true };
  fs.writeFileSync(path.join(directory, 'acceptance.json'), JSON.stringify(evidence));
  const catalog = { schema: 'pathlab.forge.release/1', commit, version: receipt.version, target: receipt.target, channel: 'candidate', inventorySha256: review.inventorySha256, sourceSha256: sourceHash,
    artifact, nativeAcceptance: { file: 'acceptance.json', sha256: sha256(path.join(directory, 'acceptance.json')) }, signatureVerification: { file: 'acceptance.json', sha256: sha256(path.join(directory, 'acceptance.json')) } };
  assert.equal(validateCatalog(directory, catalog), catalog);
  assert.throws(() => validateCatalog(directory, { ...catalog, target: 'darwin-arm64' }));
  assert.throws(() => validateCatalog(directory, { ...catalog, channel: 'stable' }));
  fs.appendFileSync(path.join(directory, 'installer.exe'), 'tamper');
  assert.throws(() => validateCatalog(directory, catalog), /Changed artifact/);
});
