# Contributing

PathLab Forge handles pathology image datasets and must be developed conservatively.

## Workflow

- Read `AGENTS.md`.
- Work from one active task.
- Use a `codex/` branch.
- Add focused tests before behavior changes.
- Keep commits reviewable.
- Stop after the requested task.

## Privacy

Do not commit source slides, patient names, local absolute paths, server credentials, generated derivatives or queue databases.

## Compatibility claims

A file format or operating system is supported only after the corresponding evidence row in `docs/compatibility/OS_MATRIX.md` and future WSI compatibility matrix is complete.

## Licensing

Do not redistribute QuPath, Bio-Formats, OpenSlide, libvips, native codecs or vendor libraries until their exact versions and licenses have been recorded in `docs/licensing/DEPENDENCY_REVIEW.md`.
