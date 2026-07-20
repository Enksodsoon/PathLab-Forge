# Codex Start Here — PathLab Forge

## One-time repository setup

This bundle is intended to become a separate private repository:

```text
Enksodsoon/PathLab-Forge
```

After extracting the bundle, use either:

```text
scripts/bootstrap-repo.ps1
```

on Windows PowerShell, or:

```text
bash scripts/bootstrap-repo.sh
```

on macOS/Linux.

The scripts initialize Git and, when GitHub CLI is installed and authenticated, create and push the private repository automatically.

## First Codex prompt

Paste only this:

```text
Open the PathLab-Forge repository.

Read AGENTS.md and docs/plans/active/current.md. Work on Task 1 only: Java 17 build foundation and batch domain state machine.

Do not add QuPath, Bio-Formats, OpenSlide, libvips, JavaFX, SQLite, server upload, WSI conversion, installers or later tasks yet.

Use test-driven development exactly as specified by the active task:
1. create the failing tests;
2. run them and confirm the expected failure;
3. implement the smallest complete solution;
4. run focused and full checks;
5. review scope and naming;
6. commit Task 1;
7. return the required TASK RESULT report;
8. stop.
```

## Why the first task is intentionally small

The project will eventually contain native readers, conversion engines, a GUI, upload recovery and multiple platform builds. Starting with a dependency-light batch state model gives later tasks a stable core without forcing Codex to solve every platform and reader problem at once.
