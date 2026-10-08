// Run on the native final artifact host. No credentials are accepted by this tool.
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { sha256, inventory } = require('../desktop/release.cjs');
const [input, artifact, app, output] = process.argv.slice(2);
if (!input || !artifact || !app || !output) throw new Error('Pass inventory.json, final installer, packaged app directory, output receipt');
const receipt = JSON.parse(fs.readFileSync(input, 'utf8'));
if (receipt.target !== `${process.platform}-${process.arch}`) throw new Error('Native signature target mismatch');
const run = (command, args) => execFileSync(command, args, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
if (process.platform === 'win32') {
  // Public store thumbprint pins the signer; private key stays in the store/HSM.
  if (!/^[A-Fa-f0-9]{40}$/.test(process.env.PATHLAB_FORGE_WINDOWS_CERT_SHA1 || '')) throw new Error('Approved signer thumbprint required');
  const files = [path.resolve(artifact), ...inventory(app).filter(file => /\.(exe|dll|node)$/i.test(file.path)).map(file => path.resolve(app, file.path))];
  const program = '$ErrorActionPreference="Stop"; $files=ConvertFrom-Json $env:PATHLAB_VERIFY_FILES; foreach($file in $files) { $s=Get-AuthenticodeSignature -LiteralPath $file; if($s.Status -ne "Valid" -or $s.SignerCertificate.Thumbprint -ne $env:PATHLAB_FORGE_WINDOWS_CERT_SHA1 -or $null -eq $s.TimeStamperCertificate) { throw "Unsigned, untrusted or untimestamped distribution binary" } }';
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', program], { stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, PATHLAB_VERIFY_FILES: JSON.stringify(files) } });
} else if (process.platform === 'darwin') {
  run('codesign', ['--verify', '--deep', '--strict', '--verbose=2', path.resolve(app)]);
  // codesign sends display metadata to stderr, verified explicitly below.
  const { spawnSync } = require('node:child_process');
  const display = spawnSync('codesign', ['--display', '--verbose=4', path.resolve(app)], { encoding: 'utf8' });
  if (display.status !== 0 || !display.stderr.includes(`Authority=${process.env.PATHLAB_FORGE_MAC_IDENTITY}`)
      || !/Timestamp=/.test(display.stderr)) throw new Error('Approved timestamped Developer ID signature required');
  run('spctl', ['--assess', '--type', 'execute', '--verbose=2', path.resolve(app)]);
  run('xcrun', ['stapler', 'validate', path.resolve(app)]);
  run('xcrun', ['stapler', 'validate', path.resolve(artifact)]);
} else throw new Error('Unsupported signing host');
fs.writeFileSync(output, JSON.stringify({ schema: 'pathlab.forge.signature-verification/1', commit: receipt.commit,
  version: receipt.version, target: receipt.target, artifactSha256: sha256(artifact), result: 'PASS',
  timestampVerified: true, nestedVerified: true, notarized: process.platform === 'darwin', stapled: process.platform === 'darwin' }, null, 2) + '\n');
