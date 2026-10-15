const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
function digest(file) {
  const hash = crypto.createHash('sha256');
  const buffer = Buffer.allocUnsafe(1024 * 1024);
  const fd = fs.openSync(file, 'r');
  try { let length; while ((length = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, length)); }
  finally { fs.closeSync(fd); }
  return hash.digest('hex');
}
function inventory(root) {
  root = fs.realpathSync(root);
  const files = [];
  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(directory, entry.name);
      const relative = path.relative(root, file).split(path.sep).join('/');
      if (entry.isSymbolicLink()) {
        if (!fs.realpathSync(file).startsWith(root + path.sep)) throw new Error('Service symlink escapes bundle');
        files.push({ path: relative, symlink: fs.readlinkSync(file) });
      } else if (entry.isDirectory()) visit(file);
      else if (entry.isFile()) files.push({ path: relative, bytes: fs.statSync(file).size, sha256: digest(file) });
      else throw new Error('Unsupported service file');
    }
  }
  visit(root);
  if (!files.length) throw new Error('Empty service inventory');
  return files;
}
function activate(root, policy, platform, arch, version) {
  if (!policy || policy.schema !== 'pathlab.forge.activation-policy/1' || policy.version !== version
      || policy.platform !== platform || policy.arch !== arch || !Array.isArray(policy.files)
      || !/^[a-f0-9]{40}$/.test(policy.commit || '') || typeof policy.sourceDirty !== 'boolean'
      || !['PRODUCTION', 'INTERNAL_NON_REDISTRIBUTABLE'].includes(policy.distribution)) throw new Error('Installed release policy is invalid. Reinstall Forge.');
  const manifest = policy.manifest;
  const production = policy.distribution === 'PRODUCTION';
  if (!manifest || manifest.platform !== platform || manifest.arch !== arch || manifest.distribution !== policy.distribution
      || manifest.internalValidation !== !production || manifest.requireProductionRuntime !== production) throw new Error('Installed release policy channel mismatch.');
  if (production) {
    if (policy.sourceDirty || manifest.version !== version) throw new Error('Production release version or source mismatch');
    const key = crypto.createPublicKey({ key: Buffer.from(manifest.featureCatalogPublicKey || '', 'base64'), format: 'der', type: 'spki' });
    if (key.asymmetricKeyType !== 'ed25519') throw new Error('Installed feature catalog trust key is invalid');
  }
  // Policy lives in app.asar; Electron's integrity fuses pin it to the signed executable.
  if (JSON.stringify(inventory(root)) !== JSON.stringify(policy.files)) throw new Error('Installed service failed integrity verification. Reinstall Forge.');
  return manifest;
}
module.exports = { activate, inventory, digest };
