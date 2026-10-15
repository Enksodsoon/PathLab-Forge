const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { collectNpm } = require('../../scripts/distribution.cjs');
test('isolated pnpm links, scoped and transitive packages are inventoried once per real root', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-npm-inventory-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ dependencies: { '@example/direct': '1.0.0' } }));
  const direct = path.join(root, 'node_modules/.pnpm/direct@1.0.0/node_modules/@example/direct');
  const transitive = path.join(root, 'node_modules/.pnpm/transitive@2.0.0/node_modules/transitive');
  for (const [directory, name, version] of [[direct, '@example/direct', '1.0.0'], [transitive, 'transitive', '2.0.0']]) {
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'package.json'), JSON.stringify({ name, version, license: 'MIT' }));
    fs.writeFileSync(path.join(directory, 'LICENSE'), 'Synthetic test notice');
  }
  fs.mkdirSync(path.join(root, 'node_modules/@example'));
  fs.symlinkSync(direct, path.join(root, 'node_modules/@example/direct'), 'junction');
  fs.symlinkSync(transitive, path.join(root, 'node_modules/.pnpm/direct@1.0.0/node_modules/transitive'), 'junction');
  const records = collectNpm(root, 'frontend', path.join(root, 'output'));
  assert.deepEqual(records.map(record => [record.name, record.version]), [['@example/direct', '1.0.0'], ['transitive', '2.0.0']]);
  assert.ok(records.every(record => record.notices.length === 1 && record.project === 'frontend' && record.decision === 'PENDING_REVIEW'));
  fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ dependencies: { missing: '1.0.0' } }));
  assert.throws(() => collectNpm(root, 'frontend', path.join(root, 'output')), /Incomplete/);
});
