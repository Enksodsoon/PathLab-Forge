# Active Task — Direct OME Delivery Release Candidate

Supersedes the previous local-pipeline task by explicit product-owner direction.

## Goal

Complete one clean, locally committed Forge-to-Viewer release candidate using
exact `ome-dynamic-v1` factor-2 negotiation, direct resumable OME upload,
persisted SHA-256 acknowledgement, native Viewer tiles, and automatic
`prepared-v2` compatibility fallback.

## Required gates

1. Preserve a clean pre-AI/TRACE/teaching Forge lineage.
2. Negotiate the complete V1 profile before conversion and again before upload.
3. Produce only the validated factor-2 OME for the accepted direct path.
4. Fail closed unless Viewer returns `ready_private` and the exact persisted SHA.
5. Maintain prepared-v2 fallback, restart/resume, privacy, storage, and library behavior.
6. Prove the real Forge-process-to-Viewer-process flow with deterministic and eligible real files.
7. Run fidelity, performance, concurrency, full regression, and launchable Windows runtime gates.

No AI, TRACE, teaching, Classroom, Jpegli, deployment, push, merge, signing, or
production activation belongs to this task.
