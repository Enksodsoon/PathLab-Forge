const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { comparePayload } = require('../installer-payload.cjs');
const { inventory } = require('../src/activation.cjs');
test('installer payload rejects older same-name bytes and unexpected files', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-payload-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'PathLabForge.exe'), 'reviewed application');
  const expected = inventory(root);
  assert.deepEqual(comparePayload(root, expected), expected);
  fs.writeFileSync(path.join(root, 'PathLabForge.exe'), 'older application');
  assert.throws(() => comparePayload(root, expected), /does not match/);
  fs.writeFileSync(path.join(root, 'PathLabForge.exe'), 'reviewed application');
  fs.writeFileSync(path.join(root, 'extra.dll'), 'unreviewed native payload');
  assert.throws(() => comparePayload(root, expected), /does not match/);
});
test('native bounded extraction rejects traversal, backslash and case collisions', { skip: process.platform !== 'win32' }, t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-safe-zip-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const create = '$ErrorActionPreference="Stop"; Add-Type -AssemblyName System.IO.Compression.FileSystem; Add-Type -AssemblyName System.IO.Compression; $z=[IO.Compression.ZipFile]::Open($env:FORGE_TEST_ZIP,[IO.Compression.ZipArchiveMode]::Create); try { foreach($n in (ConvertFrom-Json $env:FORGE_TEST_ENTRIES)) { $e=$z.CreateEntry($n); $s=[IO.StreamWriter]::new($e.Open()); try {$s.Write("synthetic test payload")} finally {$s.Dispose()} } } finally {$z.Dispose()}';
  const extract = path.resolve(__dirname, '../../scripts/windows-installer-payload.ps1');
  for (const [index, entries] of [['valid', ['lib/net45/PathLabForge.exe']], ['traversal', ['../escaped.txt']], ['backslash', ['lib\\escaped.txt']], ['collision', ['Case.txt', 'case.txt']]]) {
    const archive = path.join(root, `${index}.zip`), destination = path.join(root, index);
    execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', create], {
      env: { ...process.env, FORGE_TEST_ZIP: archive, FORGE_TEST_ENTRIES: JSON.stringify(entries) }, stdio: 'pipe' });
    const run = () => execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', extract,
      '-Installer', archive, '-Destination', destination, '-Mode', 'ZIP'], { stdio: 'pipe' });
    if (index === 'valid') { run(); assert.ok(fs.existsSync(path.join(destination, entries[0]))); }
    else assert.throws(run);
  }
  assert.equal(fs.existsSync(path.join(root, 'escaped.txt')), false);
});
