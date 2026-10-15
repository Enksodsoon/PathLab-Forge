const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { inventory } = require('./src/activation.cjs');
function comparePayload(root, expected, allowSquirrel = false) {
  const actual = inventory(root);
  const extras = actual.filter(file => !expected.some(record => record.path === file.path));
  if (!expected.length || expected.some(record => !actual.some(file => JSON.stringify(file) === JSON.stringify(record)))
      || extras.some(file => !allowSquirrel || !['squirrel.exe', 'PathLabForge_ExecutionStub.exe'].includes(file.path))) throw new Error('Installer payload does not match the reviewed application');
  return actual;
}
async function withPayload(artifact, action, platform = process.platform) {
  const managed = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-artifact-verify-'));
  const originalRoot = fs.realpathSync(managed);
  let mounted = false;
  const mount = path.join(managed, 'mounted');
  try {
    if (platform === 'win32') {
      const extracted = path.join(managed, 'extracted');
      execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', path.join(__dirname, '../scripts/windows-installer-payload.ps1'),
        '-Installer', path.resolve(artifact), '-Destination', extracted], { stdio: ['ignore', 'pipe', 'pipe'] });
      return await action(path.join(extracted, 'package/lib/net45'), path.join(extracted, 'bootstrap'));
    }
    if (platform !== 'darwin') throw new Error('Unsupported artifact extraction platform');
    fs.mkdirSync(mount);
    mounted = true;
    execFileSync('hdiutil', ['attach', '-readonly', '-nobrowse', '-noautoopen', '-mountpoint', mount, path.resolve(artifact)], { stdio: ['ignore', 'pipe', 'pipe'] });
    const apps = fs.readdirSync(mount, { withFileTypes: true }).filter(entry => entry.isDirectory() && !entry.isSymbolicLink() && entry.name.endsWith('.app'));
    if (apps.length !== 1 || apps[0].name !== 'PathLab Forge.app') throw new Error('DMG must contain exactly the matching Forge app');
    return await action(path.join(mount, apps[0].name), mount);
  } finally {
    if (mounted) { execFileSync('hdiutil', ['detach', mount], { stdio: ['ignore', 'pipe', 'pipe'] }); mounted = false; }
    // Only this newly created, resolved managed root can be removed; never installation/user data.
    if (fs.realpathSync(managed) !== originalRoot || path.dirname(originalRoot) !== fs.realpathSync(os.tmpdir())) throw new Error('Extraction cleanup root changed');
    fs.rmSync(originalRoot, { recursive: true, force: true });
  }
}
module.exports = { withPayload, comparePayload };
