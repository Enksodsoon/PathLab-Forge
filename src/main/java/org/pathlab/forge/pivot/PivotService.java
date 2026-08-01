package org.pathlab.forge.pivot;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.pathlab.forge.library.LocalDataset;

public final class PivotService {
    private final PivotRepository repository;
    private final Clock clock;

    public PivotService(PivotRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PivotService(PivotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PivotSession start(LocalDataset dataset, PivotManifest manifest) throws IOException {
        validate(dataset, manifest);
        var tasks = manifest.tasks().stream()
                .sorted(Comparator.comparingDouble(PivotTask::difficulty))
                .toList();
        var current = tasks.get(tasks.size() / 2);
        var now = clock.millis();
        var session = new PivotSession(
                UUID.randomUUID().toString(),
                dataset.id(),
                manifest.id(),
                dataset.sourceFingerprint(),
                PivotSessionState.ACTIVE,
                now,
                now,
                current.id(),
                0,
                0,
                0,
                List.of(),
                List.of());
        repository.saveSession(session);
        return session;
    }

    public Optional<PivotSession> activeSession(LocalDataset dataset) throws IOException {
        var manifest = repository.findCurrent(dataset);
        if (manifest.isEmpty()) {
            return Optional.empty();
        }
        return repository.findSession(dataset, manifest.orElseThrow());
    }

    public Optional<PivotTask> currentTask(LocalDataset dataset, PivotSession session)
            throws IOException {
        var manifest = currentManifest(dataset);
        if (!manifest.id().equals(session.manifestId())
                || session.state() != PivotSessionState.ACTIVE) {
            return Optional.empty();
        }
        return manifest.tasks().stream()
                .filter(task -> task.id().equals(session.currentTaskId()))
                .findFirst();
    }

    public PivotScore submit(
            LocalDataset dataset,
            double sourceX,
            double sourceY,
            long elapsedMs,
            double panDistance,
            int zoomReversals,
            int confidence) throws IOException {
        if (!Double.isFinite(sourceX) || !Double.isFinite(sourceY)
                || sourceX < 0 || sourceY < 0
                || sourceX > dataset.width() || sourceY > dataset.height()) {
            throw new IllegalArgumentException("Submitted PIVOT location is outside the slide");
        }
        var manifest = currentManifest(dataset);
        var session = requireActiveSession(dataset, manifest);
        var task = manifest.tasks().stream()
                .filter(candidate -> candidate.id().equals(session.currentTaskId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Current PIVOT task is unavailable"));
        var xDistance = sourceX - task.centerX();
        var yDistance = sourceY - task.centerY();
        var distance = Math.hypot(xDistance, yDistance);
        var normalized = distance / Math.max(task.targetWidth(), task.targetHeight());
        var rating = normalized <= 0.5 ? "MATCH" : normalized <= 1.25 ? "CLOSE" : "MISSED";
        var now = clock.millis();
        var attempts = new ArrayList<>(session.attempts());
        attempts.add(new PivotAttempt(
                task.id(),
                sourceX,
                sourceY,
                elapsedMs,
                panDistance,
                zoomReversals,
                confidence,
                normalized,
                rating,
                now));
        var nextTask = nextTask(
                manifest,
                attempts,
                session.skippedTaskIds(),
                task.difficulty() + (normalized <= 0.75 ? 0.18 : -0.18));
        var updated = new PivotSession(
                session.id(),
                session.datasetId(),
                session.manifestId(),
                session.sourceFingerprint(),
                nextTask.isPresent() ? PivotSessionState.ACTIVE : PivotSessionState.COMPLETED,
                session.startedAt(),
                now,
                nextTask.map(PivotTask::id).orElse(""),
                attempts.size(),
                session.skippedTasks(),
                session.hintsUsed(),
                session.skippedTaskIds(),
                attempts);
        repository.saveSession(updated);
        return new PivotScore(normalized, distance, rating, task, updated);
    }

    public PivotHint hint(LocalDataset dataset) throws IOException {
        var manifest = currentManifest(dataset);
        var session = requireActiveSession(dataset, manifest);
        var task = currentTask(dataset, session).orElseThrow();
        var horizontal = third(task.centerX(), manifest.sourceWidth(), "left", "center", "right");
        var vertical = third(task.centerY(), manifest.sourceHeight(), "top", "middle", "bottom");
        var updated = new PivotSession(
                session.id(),
                session.datasetId(),
                session.manifestId(),
                session.sourceFingerprint(),
                session.state(),
                session.startedAt(),
                clock.millis(),
                session.currentTaskId(),
                session.completedTasks(),
                session.skippedTasks(),
                session.hintsUsed() + 1,
                session.skippedTaskIds(),
                session.attempts());
        repository.saveSession(updated);
        return new PivotHint(
                "The source is in the " + horizontal + " third and " + vertical + " third.",
                updated);
    }

    public PivotSession skip(LocalDataset dataset) throws IOException {
        var manifest = currentManifest(dataset);
        var session = requireActiveSession(dataset, manifest);
        var current = currentTask(dataset, session).orElseThrow();
        var skipped = new ArrayList<>(session.skippedTaskIds());
        skipped.add(current.id());
        var next = nextTask(manifest, session.attempts(), skipped, current.difficulty());
        var updated = new PivotSession(
                session.id(),
                session.datasetId(),
                session.manifestId(),
                session.sourceFingerprint(),
                next.isPresent() ? PivotSessionState.ACTIVE : PivotSessionState.COMPLETED,
                session.startedAt(),
                clock.millis(),
                next.map(PivotTask::id).orElse(""),
                session.completedTasks(),
                skipped.size(),
                session.hintsUsed(),
                skipped,
                session.attempts());
        repository.saveSession(updated);
        return updated;
    }

    public PivotSession end(LocalDataset dataset) throws IOException {
        var manifest = currentManifest(dataset);
        var session = requireActiveSession(dataset, manifest);
        var updated = new PivotSession(
                session.id(),
                session.datasetId(),
                session.manifestId(),
                session.sourceFingerprint(),
                PivotSessionState.COMPLETED,
                session.startedAt(),
                clock.millis(),
                "",
                session.completedTasks(),
                session.skippedTasks(),
                session.hintsUsed(),
                session.skippedTaskIds(),
                session.attempts());
        repository.saveSession(updated);
        return updated;
    }

    public PivotManifest currentManifest(LocalDataset dataset) throws IOException {
        return repository.findCurrent(dataset)
                .orElseThrow(() -> new IllegalStateException(
                        "PIVOT tasks are unavailable or stale for this slide"));
    }

    private PivotSession requireActiveSession(LocalDataset dataset, PivotManifest manifest)
            throws IOException {
        var session = repository.findSession(dataset, manifest)
                .orElseThrow(() -> new IllegalStateException("Start a PIVOT session first"));
        if (session.state() != PivotSessionState.ACTIVE) {
            throw new IllegalStateException("The PIVOT session is complete");
        }
        return session;
    }

    private static Optional<PivotTask> nextTask(
            PivotManifest manifest,
            List<PivotAttempt> attempts,
            List<String> skipped,
            double desiredDifficulty) {
        var used = new HashSet<String>();
        attempts.forEach(attempt -> used.add(attempt.taskId()));
        used.addAll(skipped);
        return manifest.tasks().stream()
                .filter(task -> !used.contains(task.id()))
                .min(Comparator
                        .comparingDouble((PivotTask task) ->
                                Math.abs(task.difficulty() - desiredDifficulty))
                        .thenComparing(PivotTask::id));
    }

    private static String third(
            double position,
            double extent,
            String first,
            String middle,
            String last) {
        var fraction = position / Math.max(1, extent);
        return fraction < 1.0 / 3 ? first : fraction < 2.0 / 3 ? middle : last;
    }

    private static void validate(LocalDataset dataset, PivotManifest manifest) {
        if (!manifest.datasetId().equals(dataset.id())
                || !manifest.sourceFingerprint().equals(dataset.sourceFingerprint())
                || manifest.selectedSeries() != dataset.selectedSeries()) {
            throw new IllegalStateException("PIVOT task set is stale for the selected slide");
        }
    }
}
