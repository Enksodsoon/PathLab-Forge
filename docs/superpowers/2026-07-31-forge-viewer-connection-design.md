# Forge ↔ Viewer Local Connection Design

## State ownership

Source inspection and digest verification are concurrent observations of one persisted dataset. Inspection owns selected series, dimensions, crop and export configuration. Verification owns fingerprint and companion inventory. Verification completion reloads the newest dataset, merges only its owned fields, and derives the next status from that newest state: inspected datasets become `READY_TO_CONVERT`, uninspected VSI becomes `READER_REQUIRED`, and incomplete datasets become `NEEDS_COMPANIONS`.

The connection dialog renders one of two states. Disconnected users can enter the Viewer web origin and pair. Connected users see Viewer URL, device name and scopes and may explicitly revoke the credential. Local connection, pairing and upload state is cleared only after the Viewer acknowledges revocation.

## Transport selection

Forge fetches authenticated Viewer capabilities before upload. Direct OME is selected only when `ome-dynamic-v1` is advertised and the existing artifact has a verified pyramidal RGB OME-TIFF profile: JPEG Q75, 512-pixel tiles, factor-4 stored pyramid and globally aligned virtual levels. All other compatible artifacts use prepared-v2 generated from the existing conversion output. Network retry retains the active upload URL and confirmed offset in process and reopens the same artifact.

## Security and compatibility

Browser approval uses the Vite origin `http://127.0.0.1:5173`; Vite proxies the private desktop API on port 8000. No endpoint, scope or schema is added. Credentials remain in the OS credential store. Upload remains private and publication remains Viewer-owned.
