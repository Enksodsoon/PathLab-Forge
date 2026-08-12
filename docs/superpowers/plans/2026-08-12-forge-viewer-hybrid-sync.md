# Forge–Viewer Hybrid Private Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Forge's Viewer web links with a real authenticated private library supporting cached viewing, explicit verified offline OME downloads, and conflict-safe two-way annotation, metadata, and folder synchronization.

**Architecture:** Viewer exposes one bounded `desktop-sync/v1` service through existing device credentials. Forge persists remote identities, revisions, cursor, cache/offline state, dirty fields, and conflicts in SQLite, then drives explicit pull/push operations through one service. Existing private tiles and annotation batch contracts remain authoritative.

**Tech Stack:** Viewer: Python 3.12, FastAPI, SQLAlchemy, Alembic, SQLite, pytest. Forge: Java 21+, JDK `HttpClient`, SQLite JDBC, React 19, TypeScript, Vitest.

## Global Constraints

- No WebSocket, filesystem watcher, background daemon, new external runtime, or general synchronization framework.
- Viewer-library item pages cap at 100; change pages cap at 500.
- One full-slide download; streaming buffer at most 1 MiB; free space must exceed advertised bytes by 10 percent.
- Default tile-cache cap is 2 GiB.
- Poll every five seconds only while Viewer library is open or sync is active.
- Ordinary startup performs zero sync requests except recovery of an unfinished user-started offline download.
- Canonical slide pixels are immutable. Sync only annotations, private metadata, and folder placement.
- Conflicts preserve both sides and require Keep local, Keep Viewer, or annotation-only Keep both.
- Previously paired credentials must reconnect for `library:read`, `slides:offline:read`, and `library:sync`.
- Stop after approved hybrid-sync acceptance. No sharing, publication, collections, image replacement, arbitrary files, or live WebSockets.

---

### Task 1: Viewer sync persistence and credential scopes

**Files:**
- Modify: Viewer `server/wsi_viewer/models.py`
- Create: Viewer `migrations/versions/20260812_0016_desktop_sync.py`
- Modify: Viewer `server/wsi_viewer/desktop_routes.py`
- Test: Viewer `tests/backend/test_desktop_sync.py`

**Interfaces:**
- Produces: `DesktopSyncEvent(sequence: int, entity_type: str, entity_id: str, operation: str, revision: int, created_at: datetime)`.
- Produces scopes: `library:read`, `slides:offline:read`, `library:sync`.

- [ ] **Step 1: Write failing migration/scope tests**

```python
def test_new_pairing_receives_sync_scopes(client, admin_headers):
    token = pair_desktop(client, admin_headers)
    body = client.get('/api/v1/desktop/credential', headers=bearer(token)).json()
    assert {'library:read', 'slides:offline:read', 'library:sync'} <= set(body['scopes'])

def test_sync_event_sequence_is_monotonic(database):
    first = DesktopSyncEvent(entity_type='slide', entity_id='a', operation='upsert', revision=1)
    second = DesktopSyncEvent(entity_type='slide', entity_id='b', operation='upsert', revision=1)
    database.add_all([first, second]); database.commit()
    assert second.sequence > first.sequence

def test_existing_credential_without_sync_scope_is_rejected(client, legacy_headers):
    response = client.get('/api/v2/desktop/library/items', headers=legacy_headers)
    assert response.status_code == 403
    assert response.json()['detail']['code'] == 'DESKTOP_SCOPE_REQUIRED'
```

- [ ] **Step 2: Run red test**

Run: `python -m pytest tests/backend/test_desktop_sync.py -q`
Expected: FAIL because `DesktopSyncEvent` and scopes do not exist.

- [ ] **Step 3: Add model, migration, and exact scopes**

```python
class DesktopSyncEvent(Base):
    __tablename__ = 'desktop_sync_events'
    sequence: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    entity_type: Mapped[str] = mapped_column(String(20), nullable=False)
    entity_id: Mapped[str] = mapped_column(String(36), nullable=False)
    operation: Mapped[str] = mapped_column(String(20), nullable=False)
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
```

Append the three exact scopes to `DESKTOP_SCOPES`. Migration creates table plus `(entity_type, entity_id, sequence)` index and drops both on downgrade.

- [ ] **Step 4: Run green test and migration cycle**

Run: `python -m pytest tests/backend/test_desktop_sync.py -q`
Run: `python -m alembic upgrade head && python -m alembic downgrade 20260811_0015 && python -m alembic upgrade head`
Expected: PASS.

- [ ] **Step 5: Commit Viewer task**

```bash
git add server/wsi_viewer/models.py server/wsi_viewer/desktop_routes.py migrations/versions/20260812_0016_desktop_sync.py tests/backend/test_desktop_sync.py
git commit -m "feat: add desktop sync persistence"
```

### Task 2: Viewer bounded library and change APIs

**Files:**
- Create: Viewer `server/wsi_viewer/desktop_sync.py`
- Modify: Viewer `server/wsi_viewer/desktop_routes.py`
- Modify: Viewer `server/wsi_viewer/library_routes.py`
- Modify: Viewer `server/wsi_viewer/annotations.py`
- Test: Viewer `tests/backend/test_desktop_sync.py`

**Interfaces:**
- Produces `DesktopLibraryPage(items: list[dict], folders: list[dict], next_cursor: str | None)`.
- Produces `DesktopChangePage(changes: list[dict], next_cursor: str)`.
- Produces helpers `record_sync_event(database, entity_type, entity_id, operation, revision)` and `revision_for(updated_at) -> int` using UTC epoch microseconds.

- [ ] **Step 1: Write failing library/change tests**

```python
def test_desktop_library_is_bounded_and_private(client, sync_headers, ready_slide):
    body = client.get('/api/v2/desktop/library/items?limit=1', headers=sync_headers).json()
    assert len(body['items']) == 1
    assert body['items'][0]['id'] == ready_slide.id
    assert 'originalFilename' not in body['items'][0]

def test_changes_resume_after_cursor(client, sync_headers, ready_slide, database):
    rename_slide(database, ready_slide, 'Changed')
    first = client.get('/api/v2/desktop/library/changes?after=0&limit=1', headers=sync_headers).json()
    second = client.get(f"/api/v2/desktop/library/changes?after={first['nextCursor']}", headers=sync_headers).json()
    assert all(change['sequence'] > int(first['nextCursor']) for change in second['changes'])
```

- [ ] **Step 2: Run red test**

Run: `python -m pytest tests/backend/test_desktop_sync.py -q`
Expected: FAIL with 404 for both endpoints.

- [ ] **Step 3: Implement serializer, bounded queries, and event recording**

```python
def revision_for(value: datetime) -> int:
    aware = value if value.tzinfo else value.replace(tzinfo=UTC)
    return int(aware.timestamp() * 1_000_000)

def record_sync_event(database, entity_type, entity_id, operation, revision):
    database.add(DesktopSyncEvent(entity_type=entity_type, entity_id=entity_id,
                                  operation=operation, revision=revision))
```

Register `GET /api/v2/desktop/library/items` with `limit=48, le=100` and opaque `(updated_at,id)` cursor. Register changes with numeric durable cursor and `limit=100, le=500`. Hook existing metadata/folder/trash/restore and annotation commit paths to add one event inside their existing transaction.

- [ ] **Step 4: Run focused and existing library/annotation tests**

Run: `python -m pytest tests/backend/test_desktop_sync.py tests/backend/test_library_v2.py tests/backend/test_annotations.py -q`
Expected: PASS.

- [ ] **Step 5: Commit Viewer task**

```bash
git add server/wsi_viewer/desktop_sync.py server/wsi_viewer/desktop_routes.py server/wsi_viewer/library_routes.py server/wsi_viewer/annotations.py tests/backend/test_desktop_sync.py
git commit -m "feat: expose bounded desktop library changes"
```

### Task 3: Viewer verified OME download and revision-safe mutation

**Files:**
- Modify: Viewer `server/wsi_viewer/desktop_sync.py`
- Modify: Viewer `server/wsi_viewer/desktop_routes.py`
- Test: Viewer `tests/backend/test_desktop_sync.py`

**Interfaces:**
- Produces `HEAD/GET /api/v2/desktop/slides/{id}/content` with `Accept-Ranges: bytes`, `ETag: "{sha256}"`, `X-PathLab-SHA256`, and exact `Content-Length`.
- Produces `PATCH /api/v2/desktop/slides/{id}` body `{expectedMetadataRevision, expectedFolderRevision, displayName?, description?, caseId?, organSite?, stain?, diagnosis?, course?, tags?, teachingNote?, adminNotes?, folderId?}`.

- [ ] **Step 1: Write failing range/scope/conflict tests**

```python
def test_offline_download_resumes_exact_range(client, offline_headers, dynamic_slide):
    response = client.get(f'/api/v2/desktop/slides/{dynamic_slide.id}/content',
                          headers={**offline_headers, 'Range': 'bytes=4-'})
    assert response.status_code == 206
    assert response.headers['content-range'].startswith('bytes 4-')
    assert response.headers['x-pathlab-sha256'] == dynamic_slide.sha256

def test_patch_rejects_stale_revision(client, sync_headers, ready_slide):
    response = client.patch(f'/api/v2/desktop/slides/{ready_slide.id}', headers=sync_headers,
                            json={'expectedMetadataRevision': 1, 'expectedFolderRevision': 1,
                                  'displayName': 'Local'})
    assert response.status_code == 409
    assert response.json()['detail']['code'] == 'DESKTOP_SYNC_CONFLICT'
```

- [ ] **Step 2: Run red test**

Run: `python -m pytest tests/backend/test_desktop_sync.py -q`
Expected: FAIL with 404.

- [ ] **Step 3: Implement safe canonical target, single-range response, and compare-and-set patch**

Only `READY_PRIVATE`/`PUBLISHED`, `render_mode == 'ome_dynamic'`, persisted SHA, and canonical storage target qualify. Reject suffix/multiple ranges. Stream 1 MiB blocks. In one DB transaction, compare supplied revisions, validate folder, mutate fields, update timestamp, record event, commit, and return new revisions.

- [ ] **Step 4: Run focused tests plus security/static checks**

Run: `python -m pytest tests/backend/test_desktop_sync.py tests/backend/test_api.py -q`
Run: `python -m ruff check server tests/backend/test_desktop_sync.py`
Expected: PASS.

- [ ] **Step 5: Commit Viewer task**

```bash
git add server/wsi_viewer/desktop_sync.py server/wsi_viewer/desktop_routes.py tests/backend/test_desktop_sync.py
git commit -m "feat: add verified desktop offline download"
```

### Task 4: Forge sync contracts and durable store

**Files:**
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerRemoteSlide.java`
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerRemoteFolder.java`
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerSyncRecord.java`
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerSyncConflict.java`
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerSyncStore.java`
- Create: Forge `src/main/java/org/pathlab/forge/viewer/SqliteViewerSyncStore.java`
- Test: Forge `src/test/java/org/pathlab/forge/viewer/SqliteViewerSyncStoreTest.java`

**Interfaces:**
- `ViewerSyncStore.upsertRemote(ViewerRemoteSlide)`, `markDirty(remoteId, Set<String>)`, `saveCursor(long)`, `beginDownload(remoteId,path,length,sha)`, `advanceDownload(remoteId,offset)`, `recordConflict(ViewerSyncConflict)`, and corresponding reads.
- `ViewerSyncRecord` holds remote ID, four revisions, cache/offline paths, download offset/state, dirty fields, and conflict state.

- [ ] **Step 1: Write failing SQLite round-trip/restart tests**

```java
@Test void persistsRemoteIdentityDirtyFieldsCursorAndDownloadOffset() throws Exception {
    try (var store = new SqliteViewerSyncStore(temp.resolve("sync.db"))) {
        store.upsertRemote(slide("remote-1", 11L, 12L, 13L, 14L));
        store.markDirty("remote-1", Set.of("displayName", "folderId"));
        store.beginDownload("remote-1", temp.resolve("a.partial"), 100L, "a".repeat(64));
        store.advanceDownload("remote-1", 40L); store.saveCursor(9L);
    }
    try (var reopened = new SqliteViewerSyncStore(temp.resolve("sync.db"))) {
        assertEquals(40L, reopened.require("remote-1").downloadOffset());
        assertEquals(9L, reopened.cursor());
    }
}
```

- [ ] **Step 2: Run red test**

Run: `.\gradlew.bat test --tests '*SqliteViewerSyncStoreTest'`
Expected: compilation FAIL because store does not exist.

- [ ] **Step 3: Implement records and SQLite store**

Use tables `viewer_sync_state`, `viewer_remote_slides`, `viewer_remote_folders`, and `viewer_sync_conflicts`. Use prepared statements, WAL, transactions, normalized managed paths, JSON arrays through existing Jackson, and no static global connection.

- [ ] **Step 4: Run green test**

Run: `.\gradlew.bat test --tests '*SqliteViewerSyncStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit Forge task**

```bash
git add src/main/java/org/pathlab/forge/viewer src/test/java/org/pathlab/forge/viewer/SqliteViewerSyncStoreTest.java
git commit -m "feat: persist Viewer sync state"
```

### Task 5: Forge bounded sync client and hybrid offline service

**Files:**
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerSyncService.java`
- Create: Forge `src/main/java/org/pathlab/forge/viewer/ViewerTileCache.java`
- Modify: Forge `src/main/java/org/pathlab/forge/viewer/ViewerPairingService.java`
- Modify: Forge `src/main/java/org/pathlab/forge/library/ForgePaths.java`
- Test: Forge `src/test/java/org/pathlab/forge/viewer/ViewerSyncServiceTest.java`
- Test: Forge `src/test/java/org/pathlab/forge/viewer/ViewerTileCacheTest.java`

**Interfaces:**
- `ViewerSyncService.library(limit,cursor)`, `syncNow()`, `startOffline(remoteId)`, `cancelOffline(remoteId)`, `removeOffline(remoteId)`, `resolve(remoteId,field,resolution)`, `close()`.
- `ViewerSyncService.preview(remoteId, relativeTilePath)` returns authenticated bytes through `ViewerTileCache`.
- `ViewerTileCache.get/put(key, etag, bytes)` applies a 2 GiB default LRU cap without loading the cache inventory into memory.
- `ViewerPairingService.authorizedRequest(URI)` supplies bounded requests without exposing credentials.

- [ ] **Step 1: Write failing HTTP, cursor, resume, hash, and conflict tests**

```java
@Test void resumesOfflineDownloadFromPersistedOffsetAndActivatesOnlyMatchingHash() throws Exception {
    server.expectHead("/api/v2/desktop/slides/s1/content", 100, SHA);
    server.expectRange("/api/v2/desktop/slides/s1/content", 40, payloadFrom40);
    store.beginDownload("s1", partial, 100, SHA); store.advanceDownload("s1", 40);
    service.startOffline("s1").get();
    assertTrue(Files.exists(offline)); assertFalse(Files.exists(partial));
}

@Test void evictsLeastRecentlyUsedTilesAtConfiguredByteCap() throws Exception {
    var cache = new ViewerTileCache(temp, 10L);
    cache.put("a", "one", new byte[6]); cache.put("b", "two", new byte[6]);
    assertTrue(cache.get("a", "one").isEmpty());
    assertEquals(6L, cache.totalBytes());
}
```

- [ ] **Step 2: Run red test**

Run: `.\gradlew.bat test --tests '*ViewerSyncServiceTest'`
Expected: compilation FAIL because service does not exist.

- [ ] **Step 3: Implement bounded pull/push and offline state machine**

Use one single-thread executor, 15-second metadata timeouts, one download, 1 MiB `BodyHandlers.ofInputStream()` copy buffer, SHA-256 verification, free-space gate, `.partial` staging, and `ATOMIC_MOVE`. Do not poll from constructor. Proxy private preview metadata/tiles through an authenticated bounded response and disk LRU cache. Push annotations through existing batch schema; patch metadata/folder with expected revisions; store conflict on HTTP 409.

- [ ] **Step 4: Run focused Viewer client tests**

Run: `.\gradlew.bat test --tests '*ViewerSyncServiceTest' --tests '*ViewerPairingServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit Forge task**

```bash
git add src/main/java/org/pathlab/forge/viewer src/main/java/org/pathlab/forge/library/ForgePaths.java src/test/java/org/pathlab/forge/viewer
git commit -m "feat: synchronize private Viewer library"
```

### Task 6: Forge local API and real Viewer-library UI

**Files:**
- Modify: Forge `src/main/java/org/pathlab/forge/server/ForgeServer.java`
- Modify: Forge `frontend/src/api.ts`
- Modify: Forge `frontend/src/App.tsx`
- Modify: Forge `frontend/src/styles.css`
- Test: Forge `src/test/java/org/pathlab/forge/server/ForgeServerTest.java`
- Test: Forge `frontend/src/test/api.test.ts`
- Test: Forge `frontend/src/test/App.test.tsx`

**Interfaces:**
- Local routes: `GET /api/viewer/library`, `POST /api/viewer/sync`, `GET /api/viewer/slides/{id}/preview/{path}`, `GET/POST /api/viewer/slides/{id}/annotations`, `POST/DELETE /api/viewer/slides/{id}/offline`, `POST /api/viewer/conflicts/{id}/resolve`.
- UI types: `ViewerLibraryItem`, `ViewerFolder`, `ViewerSyncStatus`, `ViewerConflict`.

- [ ] **Step 1: Write failing local-route and UI tests**

```tsx
test('lists real Viewer slides and starts offline download', async () => {
  vi.mocked(api.viewerLibrary).mockResolvedValue({items:[remoteSlide],folders:[],nextCursor:''})
  render(<App />); fireEvent.click(await screen.findByRole('button',{name:'Viewer library'}))
  expect(await screen.findByText(remoteSlide.displayName)).toBeVisible()
  fireEvent.click(screen.getByRole('button',{name:`Keep ${remoteSlide.displayName} offline`}))
  expect(api.keepViewerSlideOffline).toHaveBeenCalledWith(remoteSlide.id)
})
```

- [ ] **Step 2: Run red tests**

Run: `.\gradlew.bat test --tests '*ForgeServerTest'`
Run: `pnpm.cmd --dir frontend exec vitest run src/test/api.test.ts src/test/App.test.tsx`
Expected: FAIL because routes/functions/UI are absent.

- [ ] **Step 3: Add local routes and replace external links**

Viewer filters operate on fetched records. Each row renders thumbnail, remote readiness, sync/offline/progress/conflict state, and Open/Keep offline/Remove offline actions. Selecting a remote row points OpenSeadragon and annotation workspace to Forge's authenticated local proxy routes; Viewer credentials never enter browser JavaScript. `Sync now` calls real sync. Poll with a five-second effect only when `libraryMode === 'viewer' && navigatorOpen`; cleanup clears timer and aborts request.

- [ ] **Step 4: Add compact conflict resolver**

Render affected fields and buttons `Keep local`, `Keep Viewer`, plus `Keep both` only for annotation conflicts. Do not display “Synced” until confirmed revisions return from server and local store commits.

- [ ] **Step 5: Run frontend, server, and bundle checks**

Run: `.\gradlew.bat test --tests '*ForgeServerTest'`
Run: `pnpm.cmd --dir frontend test -- --run`
Run: `pnpm.cmd --dir frontend build`
Expected: PASS and bundle budget passes.

- [ ] **Step 6: Commit Forge task**

```bash
git add src/main/java/org/pathlab/forge/server/ForgeServer.java src/test/java/org/pathlab/forge/server/ForgeServerTest.java frontend/src
git commit -m "feat: add real Viewer library sync UI"
```

### Task 7: Cross-repository contract and acceptance verification

**Files:**
- Create: Viewer `tests/fixtures/desktop_sync_v1/*.json`
- Create: Forge `src/test/resources/viewer-sync-v1/*.json`
- Modify: Viewer `docs/DESKTOP_INGEST_PROTOCOL.md`
- Modify: Forge `docs/contracts/PATHLAB_VIEWER_PROTOCOL.md`
- Create: Forge `docs/evidence/FORGE_VIEWER_HYBRID_SYNC.md`

**Interfaces:**
- Identical JSON fixtures for library page, change page, conflict, HEAD/range headers, and mutation response.

- [ ] **Step 1: Add shared fixtures and parser tests**

```json
{"schema":"desktop-sync/v1","items":[{"id":"slide-1","displayName":"Slide 1","imageRevision":7,"annotationRevision":3,"metadataRevision":5,"folderRevision":2,"contentBytes":1024,"contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"folders":[],"nextCursor":"7"}
```

Copy byte-identical fixtures to both repositories. Viewer serializes expected bodies; Forge parses them and rejects missing/unknown schema.

- [ ] **Step 2: Run all repository checks**

Viewer: `python -m pytest tests/backend -q && python -m ruff check server tests`
Forge: `.\gradlew.bat check installDist`
Expected: zero failures.

- [ ] **Step 3: Run installed local browser acceptance**

Start rebuilt Forge and local Viewer. Verify real remote list, thumbnail, cached open, sync polling stop, offline resume/hash, metadata/folder/annotation round trip, forced conflict, resolution, restart, responsive 390×844 layout, and zero browser errors.

- [ ] **Step 4: Record bounded evidence**

Evidence records exact Forge/Viewer SHAs, test counts, fixture identity hashes, downloaded OME bytes/SHA, resume offset, sync duration, conflict outcome, cache/offline sizes, browser viewport, and limitations. Do not claim production deployment.

- [ ] **Step 5: Commit docs and fixtures in each repository**

```bash
git add tests/fixtures/desktop_sync_v1 docs/DESKTOP_INGEST_PROTOCOL.md
git commit -m "test: verify desktop sync contract"
```

```bash
git add src/test/resources/viewer-sync-v1 docs/contracts/PATHLAB_VIEWER_PROTOCOL.md docs/evidence/FORGE_VIEWER_HYBRID_SYNC.md
git commit -m "test: verify Forge Viewer hybrid sync"
```
