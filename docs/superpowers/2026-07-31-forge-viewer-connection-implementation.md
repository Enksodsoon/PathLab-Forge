# Forge ↔ Viewer Local Connection Implementation Plan

1. Branch from preview-pyramid `00f4399`; preserve all newer queue, crop and preview work.
2. Write deterministic failing Java tests for both inspection/verification orderings. Persist inspection configuration during `VERIFYING_SOURCE`, then merge verification identity into the latest dataset record.
3. Write failing React tests for the 5173 default, disconnected pairing state, connected account details and successful-only revoke clearing. Implement the minimal state-aware dialog and actionable error mapping.
4. Write failing Java transport tests for capability selection, prepared fallback, partial-offset resume, finalization failure and retry without conversion. Port only the required capability/profile/transport pieces from the direct-OME reference commits.
5. Add Viewer component tests for invalid/valid pairing codes, authentication-required rendering and approval success; keep the Viewer API and schema untouched.
6. Run focused tests, full Forge checks and repository policy; run focused/full Viewer checks and Compose validation; then perform isolated local browser acceptance through API plus Vite and restore the existing Forge runtime.
