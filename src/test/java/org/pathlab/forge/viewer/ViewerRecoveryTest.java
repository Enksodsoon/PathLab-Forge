package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ViewerRecoveryTest {
    @TempDir Path temp;
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private static final byte[] CONTENT = "verified OME content".getBytes(StandardCharsets.UTF_8);
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static ViewerRemoteSlide slide() throws Exception {
        return new ViewerRemoteSlide("s1", "Original", "", "ready_private", 1, 0, 3, 4,
            "/thumb", "/tiles", CONTENT.length, sha(CONTENT), Map.of(), Instant.parse("2026-08-12T00:00:00Z"));
    }
    private static ViewerHttpResponse response(int status, Map<String,List<String>> headers, InputStream stream) {
        return new ViewerHttpResponse(status, headers, stream);
    }
    private static ViewerHttpResponse json(String value) { return response(200,Map.of(),new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
    private static final class Client implements ViewerAuthorizedClient {
        String key = A;
        byte[] content = CONTENT;
        InputStream stream;
        IOException patchFailure;
        String range = "";
        int requests;
        @Override public String connectionKey() { return key; }
        @Override public ViewerHttpResponse request(String method,String path,Map<String,String> headers,byte[] body) throws IOException {
            requests++;
            if (method.equals("PATCH")) throw patchFailure;
            try {
                if (method.equals("HEAD")) return response(200,Map.of("content-length",List.of(String.valueOf(CONTENT.length)),"x-pathlab-sha256",List.of(sha(CONTENT))),new ByteArrayInputStream(new byte[0]));
            } catch (Exception error) { throw new IOException(error); }
            if (path.contains("/content")) {
                boolean resumed = headers.containsKey("Range");
                return response(resumed ? 206 : 200,resumed ? Map.of("content-range",List.of(range)) : Map.of(), stream == null ? new ByteArrayInputStream(content) : stream);
            }
            throw new IOException("Unexpected request");
        }
    }

    @Test void accountSwitchSeparatesSnapshotCursorEditsAndRetainedCopies() throws Exception {
        var client = new Client();
        try (var store = new SqliteViewerSyncStore(temp.resolve("scope.db")); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            store.upsertRemote(slide()); store.saveCursor(12); store.saveIntendedMetadata("s1",Map.of("displayName","Local edit"));
            var file = service.keepOffline("s1");
            client.key = B;
            assertTrue(service.library().isEmpty()); assertEquals(0,store.cursor()); assertTrue(service.offlineFile("s1").isEmpty());
            client.key = ""; assertTrue(service.library().isEmpty()); assertTrue(service.offlineFile("s1").isEmpty());
            client.key = A;
            assertEquals("Local edit",service.library().get(0).intendedValues().get("displayName"));
            assertEquals(12,store.cursor());
            assertEquals(file,service.offlineFile("s1").orElseThrow());
        }
    }

    @Test void snapshotAndCursorRollbackTogetherWhenFolderWriteFails() throws Exception {
        try (var store = new SqliteViewerSyncStore(temp.resolve("atomic.db"))) {
            store.upsertRemote(slide()); store.saveCursor(7);
            var folder = new ViewerRemoteFolder("f1","Folder","",1);
            assertThrows(IOException.class,() -> store.replaceSnapshot(List.of(),List.of(folder,folder),9));
            assertEquals(7,store.cursor()); assertEquals("Original",store.find("s1").orElseThrow().remote().displayName()); assertTrue(store.folders().isEmpty());
        }
    }

    @Test void failedMetadataRequestPreservesExactIntendedValuesAcrossRestart() throws Exception {
        var client = new Client(); client.patchFailure = new IOException("Network offline");
        var db = temp.resolve("edits.db");
        try (var store = new SqliteViewerSyncStore(db); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            store.upsertRemote(slide()); assertThrows(IOException.class,() -> service.updateMetadata("s1","Intended value","folder-new"));
        }
        try (var store = new SqliteViewerSyncStore(db); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            assertEquals(Map.of("displayName","Intended value","folderId","folder-new"),service.library().get(0).intendedValues());
        }
    }

    @Test void fullByteCountWithWrongHashIsDurablyFailed() throws Exception {
        var client = new Client(); client.content = "x".repeat(CONTENT.length).getBytes(StandardCharsets.UTF_8);
        var db = temp.resolve("hash.db");
        try (var store = new SqliteViewerSyncStore(db); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            store.upsertRemote(slide()); assertThrows(IOException.class,() -> service.keepOffline("s1"));
            assertEquals(CONTENT.length,store.find("s1").orElseThrow().downloadOffset());
            assertEquals("FAILED",store.find("s1").orElseThrow().downloadState()); assertTrue(service.offlineFile("s1").isEmpty());
        }
    }

    @Test void retainedContentIsVerifiedAfterRestartAndInvalidatedAfterModification() throws Exception {
        var client = new Client(); var db = temp.resolve("ready.db"); Path file;
        try (var store = new SqliteViewerSyncStore(db); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            store.upsertRemote(slide()); file = service.keepOffline("s1");
        }
        try (var store = new SqliteViewerSyncStore(db); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            assertEquals(file,service.offlineFile("s1").orElseThrow());
            var requests = client.requests;
            for (int i=0;i<10;i++) assertEquals(file,service.offlineFile("s1").orElseThrow());
            assertEquals(requests,client.requests);
            Files.write(file,new byte[]{1,2,3});
            assertTrue(service.offlineFile("s1").isEmpty()); assertEquals("FAILED",service.library().get(0).downloadState());
        }
    }

    @Test void resumedContentRequiresExactContentRangeBeforeAppending() throws Exception {
        var client = new Client(); client.range = "bytes 0-18/19";
        try (var store = new SqliteViewerSyncStore(temp.resolve("range.db")); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            store.upsertRemote(slide());
            var partial = temp.resolve("offline").resolve(A).resolve("s1.ome.tif.partial");
            Files.createDirectories(partial.getParent()); Files.write(partial,Arrays.copyOf(CONTENT,3));
            store.beginDownload("s1",partial,CONTENT.length,sha(CONTENT)); store.advanceDownload("s1",3);
            assertThrows(IOException.class,() -> service.keepOffline("s1")); assertEquals(3,Files.size(partial)); assertEquals("FAILED",service.library().get(0).downloadState());
        }
    }

    @Test void cancellationClosesBlockedBodyAndPersistsCancelledState() throws Exception {
        var entered = new CountDownLatch(1); var closed = new CountDownLatch(1);
        var client = new Client(); client.stream = new InputStream() {
            public int read() throws IOException { throw new IOException("unsupported"); }
            public int read(byte[] bytes,int offset,int length) throws IOException {
                entered.countDown(); try { closed.await(5,TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                throw new IOException("closed");
            }
            public void close() { closed.countDown(); }
        };
        try (var store = new SqliteViewerSyncStore(temp.resolve("cancel.db")); var service = new ViewerSyncService(client,store,temp.resolve("offline"))) {
            store.upsertRemote(slide()); var transfer = service.keepOfflineAsync("s1");
            assertTrue(entered.await(5,TimeUnit.SECONDS)); service.cancelOffline("s1");
            assertThrows(ExecutionException.class,() -> transfer.get(5,TimeUnit.SECONDS));
            assertEquals("CANCELLED",service.library().get(0).downloadState()); assertTrue(service.offlineFile("s1").isEmpty());
        }
    }

    @Test void tileCacheAndCredentialRevocationCannotExposePreviousAccountContent() throws Exception {
        var client = new Client(); var cache = new ViewerTileCache(client,temp.resolve("cache"),100);
        var first = cache.get("/api/v2/content"); client.key=B; client.content=new byte[]{9};
        var second=cache.get("/api/v2/content"); assertNotEquals(first.path(),second.path()); assertArrayEquals(new byte[]{9},Files.readAllBytes(second.path()));
        client.key=""; assertThrows(IOException.class,() -> cache.get("/api/v2/content"));
    }
}
