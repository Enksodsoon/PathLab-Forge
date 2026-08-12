# Codex Start Here — PathLab Forge

The private repository already exists:

```text
Enksodsoon/PathLab-Forge
```

The integration plan has been re-based on the current PathLab Viewer library v2 architecture.

## First Codex prompt

Paste only this:

```text
Open Enksodsoon/PathLab-Forge.

Read AGENTS.md and docs/plans/active/current.md. Work on Task F1.1 only: Java 17 build foundation and batch domain state machine.

The active task is intentionally independent from PathLab Viewer implementation. Do not add QuPath, Bio-Formats, OpenSlide, libvips, JavaFX, SQLite, package schemas, server upload, WSI conversion, installers or later tasks.

Use test-driven development exactly as specified:
1. create the failing tests;
2. run them and confirm the expected failure;
3. implement the smallest complete solution;
4. run focused and full checks;
5. review scope and naming;
6. commit Task F1.1;
7. return the required TASK RESULT report;
8. stop.
```

## Why the first task remains small

PathLab Viewer now has folders, collections, storage accounting, cached thumbnails, publication grants, sharing and annotations. Those are server-owned systems. The first Forge task builds only a stable local batch state model, allowing later conversion and upload adapters to integrate without coupling the core to Viewer internals.
