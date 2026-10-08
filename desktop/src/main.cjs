const { app, BrowserWindow, Menu, dialog, ipcMain, session, shell } = require('electron');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { localUrl, readiness, startupFailure, externalUrl, allowedPath, windowState, serviceEnvironment } = require('./policy.cjs');
const { activate } = require('./activation.cjs');

// Squirrel invokes these during install/update; no service or data root is opened.
if (process.argv.some(value => /^--squirrel-(install|updated|uninstall|obsolete)$/.test(value))) {
  const update = path.resolve(path.dirname(process.execPath), '..', 'Update.exe');
  const remove = process.argv.includes('--squirrel-uninstall');
  if (!process.argv.includes('--squirrel-obsolete') && fs.existsSync(update)) {
    const child = spawn(update, [remove ? '--removeShortcut' : '--createShortcut', path.basename(process.execPath)], { windowsHide: true });
    child.once('exit', () => app.quit());
    child.once('error', () => app.quit());
  } else app.quit();
} else if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  let window, service, origin, desktopSecret, quitting = false, stopped = false, checkingQuit = false;
  const selected = new Set();
  const externalOrigins = [];
  const smoke = process.argv.includes('--forge-smoke-test');
  const defaultDataRoot = process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA || path.join(app.getPath('home'), 'AppData/Local'), 'PathLab Forge')
    : path.join(app.getPath('home'), 'Library/Application Support/PathLab Forge');
  const dataRoot = smoke ? fs.mkdtempSync(path.join(os.tmpdir(), 'forge-desktop-smoke-')) : defaultDataRoot;
  // Chromium state is separate so it cannot create the legacy migration target.
  const desktopRoot = smoke ? path.join(dataRoot, 'desktop') : `${dataRoot} Desktop`;
  fs.mkdirSync(desktopRoot, { recursive: true });
  app.setPath('userData', desktopRoot);
  const preferencesFile = path.join(desktopRoot, 'desktop-window.json');
  const focus = () => { if (window) { if (window.isMinimized()) window.restore(); window.show(); window.focus(); } };
  app.on('second-instance', focus);
  app.on('activate', focus);
  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
  app.on('before-quit', event => {
    if (!service || stopped) return;
    event.preventDefault();
    if (!quitting) { void requestQuit(); return; }
  });
  async function requestQuit() {
    if (checkingQuit || quitting) return;
    checkingQuit = true;
    try {
      if (origin && desktopSecret) {
        const headers = { 'X-Forge-Desktop-Secret': desktopSecret };
        const response = await fetch(`${origin}/api/desktop/lifecycle`, { headers, signal: AbortSignal.timeout(5000) });
        if (!response.ok) throw new Error('Unable to check active work');
        const work = await response.json();
        if (work.active > 0) {
          const answer = await dialog.showMessageBox(window, { type: 'question', title: 'Quit PathLab Forge?',
            message: 'Work is active. Quit will stop it safely; unfinished items can be retried after restart.',
            buttons: ['Stay', 'Stop safely and quit'], defaultId: 0, cancelId: 0 });
          if (answer.response !== 1) return;
        }
        const paused = await fetch(`${origin}/api/desktop/lifecycle`, { method: 'POST', headers, signal: AbortSignal.timeout(5000) });
        if (!paused.ok) throw new Error('Unable to preserve queue state');
      }
    } catch (error) {
      dialog.showErrorBox('Unable to quit safely', error.message); return;
    } finally { checkingQuit = false; }
    quitting = true;
    // Closing stdin interrupts bounded work while preserving durable records.
    service.stdin.end();
    const timer = setTimeout(() => {
      if (process.platform === 'darwin') {
        try { process.kill(-service.pid, 'SIGKILL'); } catch { service.kill('SIGKILL'); }
      } else service.kill();
    }, 10000);
    timer.unref();
  }

  const trusted = event => {
    if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame
        || !localUrl(event.senderFrame.url, origin)) throw new Error('Untrusted desktop caller');
  };
  const handle = (name, action) => ipcMain.handle(name, async (event, ...args) => { trusted(event); return action(...args); });
  const remember = async (values, purpose) => {
    const response = await fetch(`${origin}/api/desktop/selections`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Forge-Desktop-Secret': desktopSecret },
      body: JSON.stringify({ paths: values, purpose }), signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) throw new Error('Native selection could not be authorized');
    for (const value of values) selected.add(path.resolve(value));
    return values;
  };
  handle('forge:select-sources', async () => {
    const result = await dialog.showOpenDialog(window, { title: 'Open slide sources', properties: ['openFile', 'multiSelections'] });
    return result.canceled ? [] : remember(result.filePaths, 'import');
  });
  handle('forge:select-directory', async () => {
    const result = await dialog.showOpenDialog(window, { title: 'Open slide folder', properties: ['openDirectory'] });
    return result.canceled ? null : (await remember(result.filePaths, 'directory'))[0];
  });
  handle('forge:export-destination', async name => {
    if (typeof name !== 'string' || name.length > 200 || !name || /[\x00-\x1f/\\:]/.test(name)) throw new Error('Invalid export name');
    const result = await dialog.showSaveDialog(window, { title: 'Export', defaultPath: name });
    return result.canceled || !result.filePath ? null : (await remember([result.filePath], 'export'))[0];
  });
  handle('forge:reveal', value => {
    if (!allowedPath(value, selected)) throw new Error('Path was not selected in a native dialog');
    shell.showItemInFolder(path.resolve(value));
  });
  handle('forge:external', async value => {
    if (!externalUrl(value, externalOrigins)) throw new Error('External destination is not approved');
    await shell.openExternal(value);
  });

  function startService() {
    const root = app.isPackaged ? path.join(process.resourcesPath, 'service') : path.join(__dirname, '../resources/service');
    const manifest = app.isPackaged
      ? activate(root, require('../release-policy.json'), process.platform, process.arch, app.getVersion())
      : JSON.parse(fs.readFileSync(path.join(root, 'runtime-manifest.json'), 'utf8'));
    if (smoke && manifest.internalValidation !== true) throw new Error('Smoke requires an internal validation package');
    if (manifest.platform !== process.platform || manifest.arch !== process.arch) throw new Error('Java runtime target mismatch');
    const java = path.join(root, 'runtime/bin', process.platform === 'win32' ? 'java.exe' : 'java');
    const args = ['-Xmx512m', '--enable-native-access=ALL-UNNAMED',
      `-Dpathlab.forge.runtime.requireProduction=${manifest.internalValidation !== true}`,
      `-Dpathlab.forge.readerDataRoot=${path.join(root, 'reader-data')}`];
    if (manifest.featureCatalogPublicKey) {
      const key = require('node:crypto').createPublicKey({ key: Buffer.from(manifest.featureCatalogPublicKey, 'base64'), format: 'der', type: 'spki' });
      if (key.asymmetricKeyType !== 'ed25519') throw new Error('Installed feature catalog trust key is invalid');
      args.push(`-Dpathlab.forge.featureCatalogPublicKey=${manifest.featureCatalogPublicKey}`);
    } else if (manifest.internalValidation !== true) throw new Error('Production Forge is missing its approved feature catalog trust key. Reinstall the matching release.');
    if (manifest.viewerOrigin) {
      const viewer = new URL(manifest.viewerOrigin);
      if (viewer.protocol !== 'https:' || viewer.username || viewer.password || viewer.origin !== manifest.viewerOrigin) throw new Error('Invalid Viewer origin');
      externalOrigins.push(viewer.origin);
      args.push(`-Dpathlab.forge.viewer.defaultOrigin=${viewer.origin}`);
    }
    args.push('-cp', path.join(root, 'lib/*'), 'org.pathlab.forge.ForgeApp', '--desktop', '--data-root', dataRoot);
    const environment = serviceEnvironment(process.env);
    service = spawn(java, args, { cwd: root, env: environment, windowsHide: true,
      detached: process.platform === 'darwin', stdio: ['pipe', 'pipe', 'pipe'] });
    service.stdin.on('error', () => {});
    // Never forward child logs: readiness contains a one-use bootstrap secret.
    service.stderr.resume();
    return new Promise((resolve, reject) => {
      let buffer = '', ready = false;
      const timeout = setTimeout(() => { service.stdin.end(); reject(new Error('Local service startup timed out')); }, 60000);
      service.once('error', () => { clearTimeout(timeout); reject(new Error('Unable to start the bundled Java service')); });
      service.once('exit', () => {
        clearTimeout(timeout); stopped = true;
        if (!ready) reject(new Error('Local service exited before readiness'));
        else if (!quitting) { dialog.showErrorBox('PathLab Forge', 'The local service stopped. Reopen Forge to recover queued work.'); app.quit(); }
        else app.quit();
      });
      service.stdout.on('data', chunk => {
        if (ready) return;
        buffer += chunk.toString('utf8');
        if (buffer.length > 65536) { clearTimeout(timeout); service.stdin.end(); reject(new Error('Invalid local service startup')); return; }
        let end;
        while ((end = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, end).trim(); buffer = buffer.slice(end + 1);
          try {
            const failure = startupFailure(line);
            if (failure) { clearTimeout(timeout); service.stdin.end(); reject(new Error(failure)); return; }
            const record = readiness(line);
            if (record) { ready = true; clearTimeout(timeout); buffer = ''; resolve(record); return; }
          } catch { clearTimeout(timeout); service.stdin.end(); reject(new Error('Invalid local service readiness')); return; }
        }
      });
    });
  }

  app.whenReady().then(async () => {
    const record = await startService();
    origin = record.origin;
    desktopSecret = record.desktopSecret;
    const localSession = session.fromPartition('forge-desktop');
    localSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
    localSession.setPermissionCheckHandler(() => false);
    // Desktop assets and API use one local origin. No renderer network access to external sites.
    localSession.webRequest.onBeforeRequest((details, callback) => callback({ cancel: !localUrl(details.url, origin) }));
    let state;
    try { state = windowState(JSON.parse(fs.readFileSync(preferencesFile, 'utf8'))); } catch { state = windowState(null); }
    window = new BrowserWindow({ ...state, minWidth: 640, minHeight: 480, show: false, title: 'PathLab Forge',
      webPreferences: { preload: path.join(__dirname, 'preload.cjs'), partition: 'forge-desktop',
        sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true, devTools: !app.isPackaged } });
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    window.webContents.on('will-navigate', (event, url) => { if (!localUrl(url, origin)) event.preventDefault(); });
    window.webContents.on('will-redirect', (event, url) => { if (!localUrl(url, origin)) event.preventDefault(); });
    window.webContents.on('will-attach-webview', event => event.preventDefault());
    window.webContents.on('render-process-gone', () => { if (!quitting) { dialog.showErrorBox('PathLab Forge', 'The desktop view stopped. Reopen Forge to recover your work.'); app.quit(); } });
    window.on('close', event => {
      try {
        fs.writeFileSync(`${preferencesFile}.partial`, JSON.stringify(windowState(window.getNormalBounds())));
        fs.renameSync(`${preferencesFile}.partial`, preferencesFile);
      } catch { /* A preferences failure must not prevent service shutdown. */ }
      if (!quitting) {
        event.preventDefault();
        if (process.platform === 'darwin') window.hide();
        else app.quit();
      }
    });
    app.setAboutPanelOptions({ applicationName: 'PathLab Forge', applicationVersion: app.getVersion(),
      copyright: 'Non-AI local slide preparation and teaching tools' });
    const command = value => window.webContents.send('forge:command', value);
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
      { label: 'File', submenu: [{ label: 'Open slides…', accelerator: 'CmdOrCtrl+O', click: () => command('open-sources') },
        { label: 'Open folder…', click: () => command('open-directory') }, { type: 'separator' }, { role: 'quit' }] },
      { role: 'editMenu' },
      { label: 'View', submenu: [{ role: 'reload' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'togglefullscreen' }] },
      { role: 'windowMenu' },
      { label: 'Help', submenu: [{ label: 'Check for updates…', click: async () => {
        if (!externalOrigins[0]) { dialog.showErrorBox('Updates unavailable', 'This installation has no approved Viewer destination. Install the matching Forge release.'); return; }
        try { await shell.openExternal(`${externalOrigins[0]}/forge/downloads`); }
        catch { dialog.showErrorBox('Unable to open downloads', 'Open your approved PathLab Viewer and select Forge downloads.'); }
      } }, { label: 'About PathLab Forge', click: () => app.showAboutPanel() }] },
    ]));
    await window.loadURL(record.launchUrl);
    window.show();
    if (smoke) {
      const renderer = await window.webContents.executeJavaScript(`(async () => {
        const session = await fetch('/api/session');
        const datasets = await fetch('/api/datasets');
        const deadline = Date.now() + 10000;
        while (!document.body.innerText.includes('Choose a slide to begin') && Date.now() < deadline) {
          await new Promise(resolve => setTimeout(resolve, 50));
        }
        const unauthorizedGrant = await fetch('/api/desktop/selections', { method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Forge-Desktop-Secret': 'invalid' },
          body: JSON.stringify({ paths: ['/unselected-file'], purpose: 'import' }) });
        const rejected = async action => { try { await action(); return false; } catch { return true; } };
        return {
          noNode: typeof process === 'undefined' && typeof require === 'undefined',
          bridge: typeof window.forgeDesktop?.selectSources === 'function',
          rendered: !!document.querySelector('[aria-label="PathLab Forge"]'),
          authenticatedApi: session.ok && datasets.ok,
          workspaceReady: document.body.innerText.includes('Choose a slide to begin'),
          rendererGrantRejected: !unauthorizedGrant.ok,
          invalidIpcRejected: await rejected(() => window.forgeDesktop.openExternal('file:///outside'))
            && await rejected(() => window.forgeDesktop.revealPath('/unselected-file'))
            && await rejected(() => window.forgeDesktop.selectExportDestination('../outside'))
        };
      })()`);
      const preferences = window.webContents.getLastWebPreferences();
      const screenshot = path.join(dataRoot, 'desktop-smoke.png');
      fs.writeFileSync(screenshot, (await window.webContents.capturePage()).toPNG());
      const result = { packaged: app.isPackaged, renderer, sandbox: preferences.sandbox, screenshot,
        contextIsolation: preferences.contextIsolation, nodeIntegration: preferences.nodeIntegration, servicePid: service.pid };
      process.stdout.write(`PATHLAB_FORGE_SMOKE ${JSON.stringify(result)}\n`);
      app.quit();
    }
  }).catch(error => {
    if (smoke) { process.stdout.write('PATHLAB_FORGE_SMOKE_FAILURE integrity-or-startup\n'); app.exit(1); return; }
    dialog.showErrorBox('PathLab Forge', error.message || 'Forge could not start its bundled local service. Check that the desktop package includes the matching Java runtime and service.');
    app.quit();
  });
}
