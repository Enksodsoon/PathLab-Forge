const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const requireThat = (condition, message) => { if (!condition) throw new Error(message); };
const targets = new Set(['win32-x64', 'darwin-x64', 'darwin-arm64']);
function inventory(root) {
  root = fs.realpathSync(root);
  const files = [];
  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(directory, entry.name);
      const relative = path.relative(root, file).split(path.sep).join('/');
      if (entry.isSymbolicLink()) {
        const resolved = fs.realpathSync(file);
        requireThat(resolved.startsWith(root + path.sep), 'Inventory symlink escapes bundle');
        files.push({ path: relative, symlink: fs.readlinkSync(file) });
      } else if (entry.isDirectory()) visit(file);
      else if (entry.isFile()) files.push({ path: relative, bytes: fs.statSync(file).size, sha256: sha256(file) });
      else throw new Error('Unsupported inventory file');
    }
  }
  visit(root);
  requireThat(files.length > 0, 'Empty inventory');
  return files;
}
function exactInventory(root, expected) {
  requireThat(JSON.stringify(inventory(root)) === JSON.stringify(expected), 'Bundle inventory changed');
}
function publicKey(value) {
  requireThat(typeof value === 'string' && /^[A-Za-z0-9+/]+={0,2}$/.test(value), 'Approved feature public key required');
  const key = crypto.createPublicKey({ key: Buffer.from(value, 'base64'), format: 'der', type: 'spki' });
  requireThat(key.asymmetricKeyType === 'ed25519', 'Feature key must be Ed25519 SPKI');
  return value;
}
function validateReview(review, receipt, directory) {
  requireThat(receipt.schema === 'pathlab.forge.inventory/1' && /^[a-f0-9]{40}$/.test(receipt.commit)
    && /^\d+\.\d+\.\d+(?:-rc\.[1-9][0-9]*)?$/.test(receipt.version) && targets.has(receipt.target), 'Invalid inventory identity');
  const source = path.resolve(directory, receipt.source.file);
  requireThat(source.startsWith(path.resolve(directory) + path.sep) && sha256(source) === receipt.source.sha256, 'Source archive changed');
  for (const [file, digest] of [['java-dependencies.json', receipt.javaDependenciesSha256], ['npm-dependencies.json', receipt.npmDependenciesSha256]]) {
    requireThat(/^[a-f0-9]{64}$/.test(digest || '') && sha256(path.join(directory, file)) === digest, 'Dependency inventory changed');
  }
  requireThat(review.schema === 'pathlab.forge.distribution-review/1' && review.commit === receipt.commit
    && review.inventorySha256 === sha256(path.join(directory, 'inventory.json'))
    && review.sourceSha256 === receipt.source.sha256 && review.target === receipt.target
    && review.applicationLicense === 'GPL-3.0-or-later' && review.decision === 'APPROVED'
    && typeof review.reviewer === 'string' && review.reviewer.trim().length > 2, 'Exact distribution review required');
  for (const name of ['notices', 'correspondingSource', 'buildInstructions', 'licenseText']) {
    const record = review[name];
    requireThat(record && /^[a-f0-9]{64}$/.test(record.sha256) && typeof record.file === 'string', `Missing reviewed ${name}`);
    const file = path.resolve(directory, record.file);
    requireThat(file.startsWith(path.resolve(directory) + path.sep) && sha256(file) === record.sha256, `Changed ${name}`);
  }
  requireThat(Array.isArray(review.components) && review.components.length > 0, 'Component reviews required');
  for (const file of receipt.files.filter(file => file.sha256)) {
    requireThat(review.components.some(component => component.sha256 === file.sha256
      && component.decision === 'APPROVED' && typeof component.license === 'string'
      && !/pending|unknown|placeholder/i.test(component.license)), `Unreviewed file: ${file.path}`);
  }
}
function preflight(serviceRoot, platform, arch, env = process.env) {
  requireThat(targets.has(`${platform}-${arch}`), 'Unsupported distribution target');
  const manifest = read(path.join(serviceRoot, 'runtime-manifest.json'));
  requireThat(manifest.platform === platform && manifest.arch === arch, 'Staged Java target mismatch');
  requireThat(fs.existsSync(path.join(serviceRoot, 'runtime/bin', platform === 'win32' ? 'java.exe' : 'java'))
    && fs.readdirSync(path.join(serviceRoot, 'lib')).some(name => name.endsWith('.jar')), 'Stage the matching Java service');
  if (manifest.distribution === 'INTERNAL_NON_REDISTRIBUTABLE' && manifest.internalValidation === true
      && manifest.requireProductionRuntime === false) return false;
  requireThat(manifest.distribution === 'PRODUCTION' && manifest.internalValidation === false
    && manifest.requireProductionRuntime === true, 'Explicit distribution channel required');
  publicKey(manifest.featureCatalogPublicKey);
  requireThat(process.platform === platform && process.arch === arch, 'Production build requires native target host');
  requireThat(env.PATHLAB_FORGE_RELEASE_INPUT, 'Exact release inputs required');
  const directory = path.resolve(env.PATHLAB_FORGE_RELEASE_INPUT);
  const receipt = read(path.join(directory, 'inventory.json'));
  requireThat(receipt.schema === 'pathlab.forge.inventory/1' && receipt.target === `${platform}-${arch}`
    && receipt.version === require('./package.json').version, 'Release version/target mismatch');
  const repository = path.join(__dirname, '..');
  requireThat(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim() === receipt.commit
    && !execFileSync('git', ['status', '--porcelain', '--untracked-files=normal'], { cwd: repository, encoding: 'utf8' }).trim(), 'Release requires clean exact commit');
  requireThat(Array.isArray(receipt.dependencyInputs) && receipt.dependencyInputs.length === 4
    && receipt.dependencyInputs.every(input => ['frontend/pnpm-lock.yaml', 'desktop/pnpm-lock.yaml', 'reader-runtime.lock.properties', 'gradle/wrapper/gradle-wrapper.properties'].includes(input.file)
      && sha256(path.join(repository, input.file)) === input.sha256)
    && new Set(receipt.dependencyInputs.map(input => input.file)).size === 4, 'Dependency lock inputs changed');
  exactInventory(serviceRoot, receipt.files);
  validateReview(read(path.join(directory, 'review.json')), receipt, directory);
  return true;
}
function signing(platform, env = process.env) {
  if (platform === 'win32') {
    requireThat(/^[A-Fa-f0-9]{40}$/.test(env.PATHLAB_FORGE_WINDOWS_CERT_SHA1 || ''), 'Trusted Windows store certificate required');
    const timestamp = new URL(env.PATHLAB_FORGE_TIMESTAMP_URL || '');
    requireThat(timestamp.protocol === 'https:' && !timestamp.username && !timestamp.password
      && !/[\s"'<>]/.test(timestamp.href), 'HTTPS timestamp required');
    // Store/HSM identity: no private key or password enters a subprocess argument.
    return { windowsSign: { signWithParams: `/sha1 ${env.PATHLAB_FORGE_WINDOWS_CERT_SHA1} /fd SHA256 /tr ${timestamp.href} /td SHA256` } };
  }
  requireThat(platform === 'darwin' && /^Developer ID Application: .+ \([A-Z0-9]{10}\)$/.test(env.PATHLAB_FORGE_MAC_IDENTITY || '')
    && /^[A-Za-z0-9._-]+$/.test(env.PATHLAB_FORGE_NOTARY_PROFILE || ''), 'Trusted macOS identity and keychain profile required');
  return { osxSign: { identity: env.PATHLAB_FORGE_MAC_IDENTITY, continueOnError: false, hardenedRuntime: true,
    optionsForFile: () => ({ hardenedRuntime: true }) },
  osxNotarize: { keychainProfile: env.PATHLAB_FORGE_NOTARY_PROFILE } };
}
module.exports = { inventory, exactInventory, preflight, signing, validateReview, publicKey, sha256, targets };
