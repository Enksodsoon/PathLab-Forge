# PathLab TRACE-SIM local quick start

## Start

Double-click `C:\Users\enkso\OneDrive\Documents\PathLab Forge\Start-TRACE-SIM-Demo.cmd`.

The launcher verifies the selected model and manifest, starts only missing services, authorizes the local Forge page for the opened browser, and opens:

- Forge authoring: `http://127.0.0.1:51310/app`
- Faculty research console: `http://127.0.0.1:5173/admin/research`
- Learner Study Mode: `http://127.0.0.1:5173/study`

The local Forge pairing uses the isolated Windows Credential Manager target
`PathLabForge-TRACE-SIM-local`. It does not replace the normal production Viewer credential.

## Run a new learner demonstration

1. In the faculty console, locate the frozen `CS22-123 local TRACE-SIM workflow demonstration` protocol.
2. Select the learner tier and click **Create invitation**.
3. Copy the one-time invitation code.
4. In Study Mode, enter the code, review the exact consent artifact, and enter the study.
5. Complete the assistance-free baseline. TRACE-SIM is disabled during these measurement trials.
6. At **Baseline complete**, select **Begin optional TRACE-SIM guided practice**.
7. Record the viewport location, confidence, and source-verification response, then submit.
8. Read the three separate panels: **Observed**, **TRACE-SIM estimate**, and **Faculty-approved explanation**.
9. Refresh the faculty console to see the pseudonymous learner row. Raw outcomes and AI estimates remain in separate columns.

## Author another assignment

1. In Forge, select a privately linked slide and click **Study Pack**.
2. Select the teaching region and set its coordinate tolerance.
3. Enter the prompt, discussion instructions, approved rubric, faculty explanation, and approved HTTPS source.
4. Save the immutable version and publish it privately.
5. In the faculty console, draft a `synthetic_demo` protocol. A different administrator must approve it before it can be frozen and invited.

## Interpretation

TRACE-SIM is a real trained neural model, but its weights were trained on simulated learners. Its output is advisory and cannot determine grades. The current model demonstrates software operation and safety recovery; it does not establish effectiveness for real students.
