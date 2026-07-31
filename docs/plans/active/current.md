# Active Task — Forge ↔ PathLab Viewer Local Connection

Approved by the product owner on 2026-07-31. This milestone supersedes the batch/crop task as the single active task while preserving all completed preview-pyramid, queue, crop and conversion behavior from `00f4399`.

## Goal

Prove a complete, private, local connection from Forge to the PathLab Viewer development stack:

- repair inspection while background source verification is still running;
- make pairing, connected-account details and credential revocation one coherent lifecycle;
- prefer direct OME transport only for a Viewer advertising `ome-dynamic-v1` and an artifact matching the approved integrity/profile;
- retain prepared-v2 as the compatibility fallback without reconversion;
- validate pairing, upload, private preview, tile delivery, fallback and disconnect locally.

## Fixed boundaries

- Forge baseline: `codex/forge-ome-preview-pyramid` at `00f4399`.
- Viewer baseline: `codex/ome-shared-cache-impl` at `e58634f`.
- `87ccbad` and `5be079c` are implementation references only; their branch is not merged.
- The Viewer `/api/v1/desktop` interface and database schema remain unchanged.
- No public upload, automatic publication, push, merge, PR mutation, OCI deployment or production test.
- A failed upload reuses the existing artifact and upload offset; it never triggers reconversion.

## Ordered work

1. Add deterministic red/green regressions for inspect-before-digest and digest-before-inspect, then merge verification results into the latest dataset state atomically.
2. Make the connection dialog state-aware, default local pairing to `http://127.0.0.1:5173`, and revoke the desktop credential before clearing local state.
3. Port capability selection, approved direct OME profile validation, prepared fallback and same-process resumable retry.
4. Add Viewer web tests for pairing-code validation, approval, and sign-in-required behavior.
5. Run focused and complete Forge/Viewer checks plus isolated local browser acceptance; restore any pre-existing Forge runtime.
