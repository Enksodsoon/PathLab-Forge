package org.pathlab.forge.batch;

import java.util.List;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.library.LocalDataset;

public record BatchRun(String id, long createdAt, ArtifactRevisionFormat format, List<Item> items) {
    public BatchRun { items = List.copyOf(items); }
    public record Item(LocalDataset snapshot, String artifactRevisionId, String state, String detail, int attempts, LocalDataset initialSnapshot) {
        public Item { if (initialSnapshot == null) initialSnapshot = snapshot; }
        public Item(LocalDataset snapshot, String artifactRevisionId, String state, String detail, int attempts) {
            this(snapshot, artifactRevisionId, state, detail, attempts, snapshot);
        }
        public Item withPrepared(LocalDataset prepared) {
            return new Item(prepared, artifactRevisionId, "PREPARED", "Exact inspection settings frozen before admission", attempts, initialSnapshot);
        }
        public Item withOutcome(String artifact, String nextState, String nextDetail) {
            return new Item(snapshot, artifact, nextState, nextDetail, attempts, initialSnapshot);
        }
    }
}
