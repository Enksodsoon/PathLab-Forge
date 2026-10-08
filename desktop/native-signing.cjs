const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { execFileSync, spawnSync } = require('node:child_process');
const { inventory, sha256, signing } = require('./release.cjs');
const run = (command, args) => execFileSync(command, args, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
function machFiles(root) {
  const magic = new Set(['feedface', 'feedfacf', 'cefaedfe', 'cffaedfe', 'cafebabe', 'bebafeca', 'cafebabf', 'bfbafeca']);
  return inventory(root).filter(file => {
    if (!file.sha256 || file.bytes < 4) return false;
    const fd = fs.openSync(path.join(root, file.path), 'r');
    const bytes = Buffer.alloc(4);
    try { fs.readSync(fd, bytes, 0, 4, 0); } finally { fs.closeSync(fd); }
    // Java class files share cafebabe, but are not native fat binaries.
    return !/\.class$/.test(file.path) && magic.has(bytes.toString('hex'));
  }).map(file => path.resolve(root, file.path)).sort((a, b) => b.split(path.sep).length - a.split(path.sep).length);
}
function verifyWindows(files) {
  signing('win32');
  if (!files.length) throw new Error('No native Windows binaries found');
  const program = '$ErrorActionPreference="Stop"; $files=ConvertFrom-Json $env:PATHLAB_VERIFY_FILES; foreach($file in $files) { $s=Get-AuthenticodeSignature -LiteralPath $file; if($s.Status -ne "Valid" -or $s.SignerCertificate.Thumbprint -ne $env:PATHLAB_FORGE_WINDOWS_CERT_SHA1 -or $null -eq $s.TimeStamperCertificate) { throw "Unsigned, untrusted or untimestamped distribution binary" } }';
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', program], {
    stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, PATHLAB_VERIFY_FILES: JSON.stringify(files) } });
}
function verifyMac(files) {
  const options = signing('darwin');
  if (!files.length) throw new Error('No native macOS binaries found');
  for (const file of files) {
    run('codesign', ['--verify', '--strict', file]);
    const display = spawnSync('codesign', ['--display', '--verbose=4', file], { encoding: 'utf8' });
    if (display.status !== 0 || !display.stderr.includes(`Authority=${options.osxSign.identity}`)
        || !/Timestamp=/.test(display.stderr) || !/flags=.*runtime/.test(display.stderr)) throw new Error('Untrusted or unhardened macOS binary');
  }
}
function nativeFiles(root) {
  return process.platform === 'darwin' ? machFiles(root) : inventory(root).filter(file => /\.(exe|dll|node)$/i.test(file.path)).map(file => path.resolve(root, file.path));
}
function verifyService(root) {
  const files = nativeFiles(root);
  if (process.platform === 'win32') verifyWindows(files);
  else if (process.platform === 'darwin') verifyMac(files);
  else throw new Error('Unsupported native signing host');
}
async function signService(root) {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'runtime-manifest.json'), 'utf8'));
  if (manifest.distribution !== 'PRODUCTION' || manifest.internalValidation !== false
      || manifest.platform !== process.platform || manifest.arch !== process.arch) throw new Error('Sign only native staged production service');
  const options = signing(process.platform);
  if (process.platform === 'win32') {
    const { sign } = await import(pathToFileURL(require.resolve('@electron/windows-sign')).href);
    await sign({ ...options.windowsSign, files: nativeFiles(root) });
  } else {
    for (const file of nativeFiles(root)) run('codesign', ['--force', '--sign', options.osxSign.identity, '--timestamp', '--options', 'runtime',
      '--entitlements', path.join(__dirname, 'service-entitlements.plist'), file]);
  }
  verifyService(root);
  return { schema: 'pathlab.forge.service-signature/1', target: `${process.platform}-${process.arch}`,
    result: 'PASS', inventorySha256: require('node:crypto').createHash('sha256').update(JSON.stringify(inventory(root))).digest('hex') };
}
module.exports = { signService, verifyService, verifyWindows, verifyMac, nativeFiles, machFiles, run };
