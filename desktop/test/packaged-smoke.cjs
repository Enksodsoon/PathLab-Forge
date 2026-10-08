// Run with node test/packaged-smoke.cjs <packaged PathLabForge executable>.
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');
const path = require('node:path');
const executable = process.argv[2];
assert.ok(executable && path.isAbsolute(executable), 'Pass the actual packaged executable');
const child = spawn(executable, ['--forge-smoke-test'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
let buffer = '', record;
const timeout = setTimeout(() => { child.kill(); process.exitCode = 1; }, 90000);
child.stderr.resume();
child.stdout.on('data', chunk => {
  buffer += chunk.toString('utf8');
  assert.ok(buffer.length <= 65536, 'Unexpected desktop output');
  for (const line of buffer.split(/\r?\n/)) {
    if (line.startsWith('PATHLAB_FORGE_SMOKE ')) record = JSON.parse(line.slice('PATHLAB_FORGE_SMOKE '.length));
  }
});
child.on('error', error => { clearTimeout(timeout); throw error; });
child.on('exit', code => {
  clearTimeout(timeout);
  assert.equal(code, 0);
  assert.ok(record, 'Packaged app did not report smoke evidence');
  assert.equal(record.packaged, true);
  assert.deepEqual(record.renderer, { noNode: true, bridge: true, rendered: true, authenticatedApi: true,
    workspaceReady: true, rendererGrantRejected: true, invalidIpcRejected: true });
  assert.equal(record.sandbox, true);
  assert.equal(record.contextIsolation, true);
  assert.equal(record.nodeIntegration, false);
  assert.throws(() => process.kill(record.servicePid, 0), 'Java service survived desktop exit');
  console.log('Packaged desktop rendered; sandbox, bridge, private service and orderly shutdown passed.');
  console.log(`Screenshot: ${record.screenshot}`);
});
