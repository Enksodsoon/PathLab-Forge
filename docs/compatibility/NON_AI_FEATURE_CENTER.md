# Non-AI Feature Center (M7)

Only `pathology-tools` (PATHOLOGY) and `classical-analysis` (ANALYSIS) may occur
in a verified catalog. Pretrained AI and Training Lab remain unavailable.
No catalog URL or release public key is supplied by this change. No licensed pack,
release signing key or approved license decision is invented or committed.

The existing Ed25519 envelope signs the complete JSON descriptor list: base64
`payload` and `signature`. Pack signatures remain over UTF-8 `id`, `version` and
lowercase archive SHA-256 separated by newlines. Production descriptors now require
`platforms` (`windows-x86_64`, `macos-x86_64`, `macos-arm64`), SemVer
`minimumCoreVersion`, positive minimum memory/processor counts and
`licenseReviewStatus=APPROVED` with a nonempty license. Signed approval metadata
comes from the separately authorized catalog publisher. Missing metadata fails closed.
Core compatibility reads JAR Implementation-Version or explicit
`pathlab.forge.coreVersion`; unversioned development defaults to `0.2.0`.

Catalog refresh is explicit. The original signed envelope persists for offline
inspection and is reverified after restart. Download begins only through install.
One installer runs at a time; `progress()` and `cancelInstall(id)` stay callable
while downloading/extracting/testing. Cancellation interrupts network work and kills
the active self-test, cleans partial files, and preserves the previous active version.

Every version retains its verified archive and the signed catalog receipt.
Activation verifies the signature, complete archive hash and every extracted file
against the archive, then runs its Java 17 self-test with an adaptive heap and a
30-second deadline. Native/script entrypoints, unsafe/duplicate/device paths,
unmanifested files and symbolic links are rejected. Readers remain installed in
their independent core runtime. This is a capability gate, not a general plugin loader.

A single atomically written active.json selects the exact active and previous
version plus enabled state. New versions never replace older version directories.
Failed activation keeps the old pointer. Restart revalidates active content and
self-test; failure restores the exact verified previous version, or leaves the pack
inactive if no verified fallback exists. Disable keeps installed versions; enable
revalidates the selected version; rollback selects the previous verified version.
Uninstall removes only the code-only feature-packs/<id> subtree. Settings, annotations,
analysis results and other user files outside that subtree are preserved.

List responses expose available `version`, `activeVersion` and `installedVersions`;
version selection follows SemVer precedence, including prerelease numbers.
Backend helpers: install, enable, disable, activate(id, version), rollback,
uninstall, activeVersion, progress and cancelInstall. Root HTTP handlers own
explicit user actions and background execution.

Validation uses synthetic Java self-test fixtures and ephemeral test signing keys;
these are not published packs, commercial licenses or release keys. Windows Java
contracts cover offline reload, signature/hash rejection, platform/memory/CPU/core
compatibility, safe extraction, cancellation, activation, disable/enable, exact
rollback, tamper recovery and preservation of settings/results. No real signed
catalog or downloaded licensed non-AI pack is qualified by these synthetic tests.

Final Windows check: complete Java suite 244 tests, zero failures/errors, eight
existing real-fixture skips; nine Feature Center contract tests passed. Gradle
configuration cache reused, frontend production build/bundle budget passed,
repository policy and `git diff --check` passed. Windows staging handle sharing
was reproduced during upgrade; shared managed move/delete now has a two-second
retry ceiling and preserves the primary error if cleanup also fails.
