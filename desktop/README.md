# Forge desktop

The Java 17 service owns SQLite, streaming slide work and loopback API. Electron
owns one sandboxed window, native dialogs, menus and the service lifetime. The
renderer has no Node access. Startup secrets travel only over a private stdout
pipe; closing the owner's stdin requests service shutdown.

Stage the host runtime with `gradlew stageElectronService`, then in this directory
run `pnpm install --frozen-lockfile`, `pnpm test`, `pnpm run check` and `pnpm start`.
`pnpm package` creates the app image and `pnpm make` creates the host installer.
For macOS use `pnpm make --arch=x64` or `--arch=arm64` on the matching host with
the matching staged Java runtime. Packaging rejects a mismatched runtime manifest.
The service resource layout is `resources/service/{lib,runtime}` with
`runtime-manifest.json` declaring Electron `platform`, `arch` and optional
configured HTTPS `viewerOrigin`. Runtime binaries and installers stay untracked.

The narrow `window.forgeDesktop` bridge returns native selections to the existing
authenticated API client: `selectSources()`, `selectDirectory()`,
`selectExportDestination(name)`, `revealPath(path)`, `openExternal(url)` and
`onCommand(callback)`. Commands are `open-sources` and `open-directory`.
Native selections receive main-secret grants before reaching the renderer.
Reveal accepts only paths selected in a native dialog. External
destinations accept only HTTPS at the packaged Viewer origin or GitHub docs.

Squirrel.Windows and DMG maker configurations follow the
[Forge maker documentation](https://www.electronforge.io/config/makers).
Signing, notarization, paid certificates and publishing are deliberately absent.
macOS artifacts require actual Intel/Apple Silicon validation before support claims.

For an internal validation manifest (`internalValidation: true`), run
`node test/packaged-smoke.cjs <absolute packaged executable>` after packaging.
This launches the actual artifact with an isolated temporary library, checks
authenticated API access and renderer sandbox/IPC rejection, captures a screenshot,
and asserts that the Java service exits with the desktop. Internal validation
artifacts are unsigned and NON_REDISTRIBUTABLE; they do not qualify production readers.
