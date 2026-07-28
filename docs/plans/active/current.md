# Active Task F1.1 — Java 17 Build Foundation and Batch Domain State Machine

> Complete this task only. Commit, report and stop.

## Goal

Create a dependency-light Java 17 project that compiles and tests on the development platform, with explicit immutable batch/job domain models and a validated state-transition function.

This task remains valid after the latest PathLab Viewer review because it defines only Forge’s local workflow. It deliberately does not copy Viewer’s database states or server implementation.

Do not add WSI readers, image conversion, JavaFX, SQLite, server upload, native libraries, package schemas or installers.

## Create

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
src/main/java/org/pathlab/forge/ForgeApp.java
src/main/java/org/pathlab/forge/model/BatchId.java
src/main/java/org/pathlab/forge/model/JobId.java
src/main/java/org/pathlab/forge/model/JobState.java
src/main/java/org/pathlab/forge/model/SlideJob.java
src/main/java/org/pathlab/forge/model/InvalidJobTransition.java
src/main/java/org/pathlab/forge/model/JobTransitions.java
src/test/java/org/pathlab/forge/model/JobTransitionsTest.java
src/test/java/org/pathlab/forge/model/SlideJobTest.java
```

Create a Gradle wrapper and CI only after focused tests pass.

## Required model behavior

- `BatchId` and `JobId` are non-empty value objects.
- `SlideJob` is immutable.
- `SlideJob` contains job ID, batch ID, local source path string, display name, queue position, current state, retry count and created/updated timestamps.
- No WSI reader, GUI, database, package or HTTP object appears in the model.
- Queue position is non-negative.
- Retry count is non-negative.
- Source path remains local and is never treated as a server manifest field.

Required Forge states:

```text
PENDING
INSPECTING
NEEDS_REVIEW
READY
EXPORTING_OME
VALIDATING_OME
GENERATING_DZI
VALIDATING_DZI
PACKAGING
READY_TO_UPLOAD
UPLOADING
SERVER_PROCESSING
READY_PRIVATE
PUBLISHED
PAUSED
CANCEL_REQUESTED
CANCELLED
FAILED_RETRYABLE
FAILED_PERMANENT
SKIPPED
```

`SERVER_PROCESSING` intentionally abstracts the current Viewer sequence `queued → validating → converting`. Store exact server state later as separate job diagnostics rather than adding Viewer-specific states to the core state machine.

`JobTransitions.transition(source, target)` returns the target only for explicitly allowed transitions and throws `InvalidJobTransition` otherwise.

At minimum, test:

- normal local conversion path;
- upload and server-processing path;
- failure and retry path;
- pause and resume path;
- cancellation path;
- terminal-state rejection;
- invalid direct jumps;
- immutable job update returning a new object.

## Test-first sequence

1. Verify current official Gradle tooling suitable for a Java 17-compatible project; record selected versions only in build files.
2. Create failing model and transition tests.
3. Run and confirm failure due to missing classes.
4. Implement the smallest domain model and explicit transition table.
5. Run focused tests.
6. Add Gradle wrapper.
7. Add `.github/workflows/ci.yml` running tests on Windows, macOS and Ubuntu with Java 17.
8. Run complete tests and repository verification scripts.
9. Review for reader, GUI, database, package and HTTP dependency leakage.
10. Commit and stop.

## Acceptance criteria

- Java 17-compatible compilation succeeds.
- All transition behavior is explicit and tested.
- Domain models contain no reader, GUI, database, package or HTTP dependency.
- CI configuration covers Windows, macOS and Ubuntu.
- No later milestone functionality is implemented.
- Repository policy checks pass.

## Required report

```text
TASK RESULT
- Task: F1.1 Java 17 build foundation and batch domain state machine
- Branch:
- Commit:
- Files changed:
- Focused tests:
- Full checks:
- Evidence:
- Known limitations:
- Next task: F1.2 SQLite queue persistence and restart recovery — do not start
```
