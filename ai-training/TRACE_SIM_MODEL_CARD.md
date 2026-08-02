# TRACE-SIM 3M demonstration model card

## Intended use

TRACE-SIM is a local educational workflow demonstration. It estimates delayed retention, effort, hint need, confidence-calibration risk, and source-verification risk from interaction telemetry. It may rank a bounded set of practice actions in an explicitly enabled `synthetic_demo` protocol.

It does not inspect slide pixels, diagnose disease, generate answer keys, grade free text, determine grades, or operate during baseline/follow-up measurement. Its output is advisory and is always labeled `TRACE-SIM · trained on simulated learners`.

## Training data and scope

- Smoke corpus: 128,000 deterministic events.
- Integration corpus: 1,280,000 deterministic events, 48/48 scenario families covered.
- Counterfactual pairs vary exactly one of confidence, hint use, or navigation effort.
- The 10.56-million tier was not generated because integration coverage was complete; generating redundant data would violate the prespecified expansion rule.
- No patient data, WSI pixels, PHI, real learner records, or medical facts are present.

Synthetic performance demonstrates software recovery only. It is not evidence that TRACE-SIM improves learning, works for real students, or is valid for medical-education research outcomes.

## Selected artifact

- Architecture: five-head TRACE-Former student, nominal 3M class, 256-event maximum context.
- Runtime: ONNX dynamic int8, WebAssembly fallback with optional WebGPU.
- Artifact size: 3,257,665 bytes.
- Artifact SHA-256: `9ca7e812951712eb29fd24c1fbf825afdb0b8a743ed941d96e186dab4d90c8a1`.
- Checkpoint SHA-256: `2d625b1fad5c97584e1f7c69c3a95a6761fd934adaf17b1cecce329247e9fa0d`.

Three learner-disjoint seeds produced mean five-head Brier scores of 0.01622, 0.01482, and 0.01538 (mean 0.01547; sample SD 0.00070). These numbers are synthetic recovery metrics, not human-effectiveness estimates.

A bounded 32M teacher check produced a worse 0.03637 mean Brier. The search therefore stopped under the prespecified continue-only-while-improving rule; 8M/15M distillation was not run because the available teacher was inferior. The failed teacher checkpoint and Pareto report remain in the local artifact store.

Across 500 changed-target counterfactual pairs per feature, directional response accuracy was 1.000 for confidence→calibration risk, 1.000 for hint use→hint need, and 0.998 for navigation effort→effort. Mean absolute probability responses were 0.835, 0.985, and 0.732 respectively. These are simulator-recovery checks only.

## Runtime and safety validation

On the current 32-GB, 12-logical-CPU PC with the runtime limited to six CPU threads:

- ONNX p95 inference: 0.56 ms.
- Incremental runtime memory: 11.1 MB.
- Artifact: 3.26 MB.
- OOD rejection recall on prespecified malformed/out-of-range cases: 1.00.
- ONNX parity: every head within 0.01 Brier and 0.02 AUROC of PyTorch.
- Every synthetic in-family head: AUROC above 0.99 and ECE below 0.021.

These measurements fit the 8-GB/6-core reference budget, but the reference-device statement is an engineering budget assessment, not a measurement performed on a separate physical 8-GB computer.

## Fail-safe behavior

Missing or tampered artifacts, hash mismatch, disabled feature flag, non-`synthetic_demo` protocol, measurement phase, missing/non-finite inputs, OOD state, high uncertainty, or runtime failure returns fixed-safe delivery. Client predictions are stored only as untrusted synthetic-demo audit evidence. Released weights do not update online.

## Reproduction and artifacts

Run `scripts/run_trace_sim_pipeline.ps1` from `ai-training` to generate, train three seeds, export, and validate. Large Parquet shards, checkpoints, ONNX files, immutable manifests, and reports are stored under `%LOCALAPPDATA%\PathLab Forge\adapt\trace-sim`, outside Git.

## Limitations

The simulator encodes assumptions chosen by the developers and can make recovery deceptively easy. It cannot establish construct validity, fairness, calibration for actual learners, clinical safety, educational benefit, or causal effect. Confidence and source-risk outputs must remain demo/shadow outputs until separately approved real data validates them. Faculty-approved explanations and citations—not this model—supply all medical content.
