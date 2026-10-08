const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { readiness, startupFailure, localUrl, externalUrl, allowedPath, windowState, serviceEnvironment } = require('../src/policy.cjs');
test('startup failures display fixed actionable messages without service details', () => {
  assert.equal(startupFailure('ordinary output'), null);
  assert.match(startupFailure('PATHLAB_FORGE_FAILED {"protocol":1,"code":"DATA_LOCKED","detail":"secret"}'), /Another Forge process/);
  assert.throws(() => startupFailure('PATHLAB_FORGE_FAILED {"protocol":1,"code":"secret"}'));
});
test('private readiness rejects remote, credentialed and mismatched origins', () => {
  const line = value => 'PATHLAB_FORGE_READY ' + JSON.stringify({ desktopSecret: 'a'.repeat(43), ...value });
  assert.equal(readiness('ordinary service output'), null);
  assert.equal(readiness(line({ protocol: 1, origin: 'http://127.0.0.1:1234/', launchUrl: 'http://127.0.0.1:1234/?launchToken=x' })).origin, 'http://127.0.0.1:1234');
  for (const origin of ['https://example.com/', 'http://localhost:1234/', 'http://x@127.0.0.1:1234/', 'http://127.0.0.1:1234/?x']) {
    assert.throws(() => readiness(line({ protocol: 1, origin, launchUrl: origin })));
  }
  assert.equal(localUrl('http://127.0.0.1:4321/app', 'http://127.0.0.1:1234'), false);
});
test('bundled service ignores ambient JVM and reader overrides', () => {
  assert.deepEqual(serviceEnvironment({ PATH: 'system', HOME: 'home', JAVA_TOOL_OPTIONS: '-javaagent:evil',
    JDK_JAVA_OPTIONS: '-cp evil', _JAVA_OPTIONS: '-Xmx1m', CLASSPATH: 'evil', JAVA_HOME: 'external',
    LD_PRELOAD: 'evil', DYLD_LIBRARY_PATH: 'evil', PATHLAB_FORGE_RUNTIME_ROOT: 'external', pathlab_forge_token: 'secret' }),
  { PATH: 'system', HOME: 'home' });
});
test('external destinations and native reveal fail closed', () => {
  assert.equal(externalUrl('https://viewer.example/app', ['https://viewer.example']), true);
  for (const url of ['file:///tmp', 'javascript:alert(1)', 'https://viewer.example.evil/app', 'https://x@viewer.example']) {
    assert.equal(externalUrl(url, ['https://viewer.example']), false);
  }
  const root = path.resolve('data');
  const file = path.join(root, 'exports', 'a');
  assert.equal(allowedPath(file, new Set([file])), true);
  assert.equal(allowedPath(file, new Set()), false);
  assert.equal(allowedPath('../outside', new Set()), false);
  assert.deepEqual(windowState({ width: -1, height: Infinity }), { width: 1280, height: 840 });
});
