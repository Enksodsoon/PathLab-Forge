# PIVOT MVP Acceptance Evidence

Date: 2026-08-01

## Automated acceptance

- `gradlew test installDist`: passed, including Java compiler, repository,
  session-service, CSRF/API, package-entry, and geometry tests.
- Frontend production build: passed TypeScript, Vite, and bundle budgets.
- Frontend interaction suite: 35 tests passed after the final accessibility and
  responsive changes.
- Production application payload: `app.js` 76,718 bytes and 22.38 KB gzip.

## Real browser acceptance

The production desktop server was exercised through its one-time local browser
authorization flow with an existing unannotated converted VSI. The browser run
verified:

- the compact packaged DZI is sampled directly, without restoring loose tiles;
- 512 candidates produced 12 tasks in 2,626 ms;
- the PIVOT run stored 15 files totaling 714,945 bytes (0.68 MiB);
- query rendering, whole-slide navigation, click selection, coordinate scoring,
  target reveal after scoring, hint, skip controls, and end control;
- a miss at Challenge difficulty selected a Moderate next task;
- completed count, hint count, attempt telemetry, and current task survived an
  application reload;
- no browser console errors;
- desktop acceptance at 1600 x 1000 and narrow acceptance at 520 x 900.

The observed local Forge Java process tree after compilation used approximately
360 MiB working set. This is an observation on the acceptance host, not an 8 GB
hardware certification or a claim of peak memory delta.

The real-slide screenshots remain local acceptance artifacts and are deliberately
excluded from the repository because the source filename and tissue image are not
public-repository-safe.

## Fidelity ledger

Reference: `docs/design/PIVOT_WORKSPACE_CONCEPT.png`.

| Comparison point | Result |
| --- | --- |
| Three-column task/viewer/progress structure | Matched at desktop width |
| Persistent bottom confidence and action bar | Matched |
| White chrome, dark slide stage, teal action language | Matched |
| Query image, task instruction, and non-diagnostic notice | Matched with real generated query data |
| Difficulty and recent-performance rail | Matched; uses completed/hints/scale gap rather than attempt allowance |
| Viewer navigation controls and minimap | Matched using the existing OpenSeadragon viewer |
| Narrow behavior | Intentionally becomes a vertical flow with a sticky action bar; query remains visible before the viewer |
| Copy | Uses "source coordinates" and "target widths" instead of millimetres when physical calibration is absent |

Intentional deviations:

- No attempt quota is shown because the MVP records one score per presented task.
- Distance is normalized by target size, not reported in millimetres; Forge never
  invents physical scale.
- Desktop window chrome and training-mode toggle from the concept are omitted
  because the current application is a secured local web shell.
- The generated concept contains a fictional H&E example; the implemented view
  always renders the learner's actual local DZI and generated query image.
