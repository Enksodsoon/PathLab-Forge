// Internal test image only: restores every modified byte, never opens user data.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const executable = process.argv[2];
assert.ok(executable && path.isAbsolute(executable), 'Pass the actual packaged executable');
const resources = process.platform === 'darwin' ? path.resolve(path.dirname(executable), '../Resources') : path.join(path.dirname(executable), 'resources');
const manifestFile = path.join(resources, 'service/runtime-manifest.json');
assert.equal(JSON.parse(fs.readFileSync(manifestFile, 'utf8')).distribution, 'INTERNAL_NON_REDISTRIBUTABLE', 'Only mutate an internal test image');
const files = [manifestFile, path.join(resources, 'service/lib', fs.readdirSync(path.join(resources, 'service/lib')).find(name => name.endsWith('.jar'))),
  path.join(resources, 'service/runtime/bin', process.platform === 'win32' ? 'java.exe' : 'java'), path.join(resources, 'app.asar')];
for (const file of files) {
  const original = fs.readFileSync(file);
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  try {
    if (file.endsWith('app.asar')) {
      const modified = Buffer.from(original);
      const offset = modified.indexOf(Buffer.from('INTERNAL_NON_REDISTRIBUTABLE'));
      assert.ok(offset >= 0, 'Protected policy missing from archive');
      modified[offset] = 'X'.charCodeAt(0);
      fs.writeFileSync(file, modified);
    } else fs.appendFileSync(file, '\n');
    const result = spawnSync(executable, ['--forge-smoke-test', '--noerrdialogs'], { windowsHide: true, timeout: 30000, encoding: 'utf8' });
    assert.notEqual(result.status, 0, `Tampered ${path.basename(file)} launched successfully`);
    assert.ok(!result.error, `Tampered ${path.basename(file)} did not exit promptly`);
    assert.doesNotMatch(result.stdout || '', /PATHLAB_FORGE_SMOKE \{/);
  } finally { fs.writeFileSync(file, original); }
  assert.equal(hash(fs.readFileSync(file)), hash(original), 'Internal image restoration failed');
}
console.log('Actual internal image rejected altered manifest, JAR, JVM and app archive. All bytes restored.');
