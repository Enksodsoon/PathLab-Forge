# Active Task 1 — Java 17 Build Foundation and Batch Domain State Machine

> Complete this task only. Commit, report and stop.

## Goal

Create a dependency-light Java 17 project that compiles and tests on the development platform, with explicit immutable batch/job domain models and a validated state-transition function.

Do not add WSI readers, image conversion, JavaFX, SQLite, server upload, native libraries or installers.

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

Create a Gradle wrapper and CI only after the focused tests pass.

## Required model behavior

- `BatchId` and `JobId` are non-empty value objects.
- `SlideJob` is immutable.
- `SlideJob` contains job ID, batch ID, source path string, display name, queue position, current state, retry count and created/updated timestamps.
- No WSI library object appears in the model.
- Queue position is non-negative.
- Retry count is non-negative.
- Source path is stored only locally and is not a server manifest field.

Required states:

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
SERVER_IMPORTING
READY_PRIVATE
PUBLISHED
PAUSED
CANCEL_REQUESTED
CANCELLED
FAILED_RETRYABLE
FAILED_PERMANENT
SKIPPED
```

`JobTransitions.transition(source, target)` returns the target only for explicitly allowed transitions and throws `InvalidJobTransition` otherwise.

At minimum, test:

- normal local conversion path;
- upload/import path;
- failure and retry path;
- pause/resume path;
- cancellation path;
- terminal-state rejection;
- invalid direct jumps;
- immutable job update returning a new object.

## Test-first sequence

1. Verify the official current Gradle/Java tooling needed for a Java 17-compatible project; record only the selected versions in the build files.
2. Create failing model and transition tests.
3. Run and confirm failure due to missing classes.
4. Implement the smallest domain model and explicit transition table.
5. Run focused tests.
6. Add Gradle wrapper.
7. Add `.github/workflows/ci.yml` running tests on Windows, macOS and Ubuntu with Java 17.
8. Run complete tests and repository verification scripts.
9. Review for future-reader dependency leakage and scope creep.
10. Commit and stop.

## Acceptance criteria

- Java 17-compatible compilation succeeds.
- All transition behavior is explicit and tested.
- Domain models contain no reader, GUI, database or HTTP dependency.
- CI configuration covers Windows, macOS and Ubuntu.
- No later milestone functionality is implemented.
- Repository policy checks pass.

## Required report

```text
TASK RESULT
- Task: Java 17 build foundation and batch domain state machine
- Branch:
- Commit:
- Files changed:
- Focused tests:
- Full checks:
- Evidence:
- Known limitations:
- Next task: SQLite queue persistence and restart recovery — do not start
```
