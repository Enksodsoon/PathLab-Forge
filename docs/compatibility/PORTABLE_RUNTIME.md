# Portable runtime implementation (M3)

The backend stays on Java 17. `stageElectronService` stages the application jars,
matching Java 17 runtime and host platform/architecture manifest under the ignored
`desktop/resources/service` tree. Package on the target OS/architecture; cross
packaging is rejected. Electron consumes this service without a system Java lookup.
Host-native jpackage reader images place mac reader data in
`PathLab Forge.app/Contents/Resources/reader-data`; Windows uses adjacent `reader-data`.

Production reader assembly requires an approved redistribution status, notices for
every included component, exact platform, complete inventory fingerprint and pinned
Bio-Formats/libvips hashes. `reader-runtime.lock.properties` deliberately remains
pending. Approval is an external licensing decision; these checks do not grant rights.
The mandatory reader scope remains TIFF/SVS/VSI. SDPC remains optional, Windows-only
and blocked for production while its exact redistribution review is pending.

Runtime staging rejects directory symlinks. Reviewed mac file symlinks require
`macos.file-symlinks.status=APPROVED` in the external runtime review and must resolve
inside the same component. Staging dereferences these file links into ordinary files
and verifies their hashes again; escaping links, directory links and unreviewed links
fail closed. Review native loader compatibility on the real mac package.

macOS credentials use the login Keychain through the existing JNA dependency.
Credential values never enter CLI arguments or plaintext files. macOS defaults to
`~/Library/Application Support/PathLab Forge`. Before locking the new data root,
`ForgePaths.migrateLegacyMacData()` copies legacy `~/.pathlab-forge` under the legacy
ownership lock and a migration lock, then atomically renames staging. The original
remains intact. Existing target data is preserved; incomplete staging requires
recovery and is never silently discarded.

Windows containment reports whether native Job assignment actually succeeded and
uses adaptive hardware limits. Other platforms report best-effort process tree
termination without claiming native memory enforcement. RSS samples contain measured
and total process counts; complete RSS is unavailable (`-1`) when any process could
not be measured. macOS uses native `proc_pid_rusage` resident size, never JVM heap as
process-tree RSS.

Windows-only checks: Java compilation, portable unit contracts, repository policy,
full Java tests and staged service layout. Actual mac Keychain, native RSS, executable
permissions, dylib loading, signed/notarized app launch and TIFF/SVS/VSI fixtures are
unverified on this Windows host. Neither mac architecture is supported until the
OS_MATRIX artifact qualification passes on that actual platform.

Native ABI references: [Apple Keychain lookup](https://developer.apple.com/documentation/security/seckeychainfindgenericpassword(_:_:_:_:_:_:_:_:)),
[Apple libproc declaration](https://github.com/apple-oss-distributions/xnu/blob/main/libsyscall/wrappers/libproc/libproc.h)
and [rusage structure](https://github.com/apple/darwin-xnu/blob/main/bsd/sys/resource.h).
The generic Keychain API is deprecated but retained for the scoped compatibility
range; real macOS credential round trips remain a release qualification requirement.
