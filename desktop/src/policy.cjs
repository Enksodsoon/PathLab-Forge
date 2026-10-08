const path = require('node:path');
function localUrl(value, origin) {
  try {
    const url = new URL(value);
    return url.origin === origin && !url.username && !url.password;
  } catch { return false; }
}
function readiness(line) {
  if (!line.startsWith('PATHLAB_FORGE_READY ')) return null;
  const record = JSON.parse(line.slice('PATHLAB_FORGE_READY '.length));
  const url = new URL(record.origin);
  if (record.protocol !== 1 || url.protocol !== 'http:' || url.hostname !== '127.0.0.1'
      || !url.port || url.username || url.password || url.pathname !== '/' || url.search || url.hash
      || !localUrl(record.launchUrl, url.origin)
      || typeof record.desktopSecret !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(record.desktopSecret)) throw new Error('Invalid private readiness record');
  return { origin: url.origin, launchUrl: record.launchUrl, desktopSecret: record.desktopSecret };
}
function externalUrl(value, origins) {
  if (typeof value !== 'string' || value.length > 4096) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && origins.includes(url.origin);
  } catch { return false; }
}
function allowedPath(value, selected) {
  if (typeof value !== 'string' || value.length > 32768 || value.includes('\0') || !path.isAbsolute(value)) return false;
  return selected.has(path.resolve(value));
}
function windowState(value) {
  const state = { width: 1280, height: 840 };
  if (value && Number.isInteger(value.width) && value.width >= 640 && value.width <= 8192
      && Number.isInteger(value.height) && value.height >= 480 && value.height <= 8192) {
    state.width = value.width; state.height = value.height;
  }
  return state;
}
function serviceEnvironment(environment) {
  return Object.fromEntries(Object.entries(environment).filter(([key]) =>
    !/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH|JAVA_HOME|LD_PRELOAD|DYLD_INSERT_LIBRARIES|DYLD_LIBRARY_PATH)$/i.test(key)
    && !/^PATHLAB_FORGE_/i.test(key)));
}
module.exports = { localUrl, readiness, externalUrl, allowedPath, windowState, serviceEnvironment };
