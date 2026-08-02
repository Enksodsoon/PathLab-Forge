# TRACE-Former research pipeline

`pathlab_adapt` is an offline, content-agnostic educational research package.
It does not inspect slide pixels, create medical answer keys, make clinical or
human-benefit claims, download datasets, or update released model weights.

The base package uses the Python standard library. PyTorch teacher, student,
GRU, Transformer, and distillation paths require `.[adapt-model]`; atomic ONNX
int8 export additionally requires `.[adapt-export]`. Base CI does not use the
network, a GPU, real datasets, or training loops.

## Local data preparation

Every real source must have a versioned license-ledger entry containing its URL,
license, permitted use, redistribution and derivative/model restrictions,
retrieval date, and archive checksum. The adapters accept existing local files:

```powershell
pathlab-adapt adapt-oulad --student-vle D:\OULAD\studentVle.csv `
  --pseudonym-salt $env:PATHLAB_ADAPT_SALT --output D:\TRACE\oulad.jsonl

pathlab-adapt adapt-ednet --root D:\EdNet\KT1 `
  --pseudonym-salt $env:PATHLAB_ADAPT_SALT --event-cap 5000000 `
  --license-ledger D:\TRACE\license-ledger.json `
  --output D:\TRACE\ednet.jsonl
```

EdNet is hard-capped at 5,000,000 streamed events. `TRACE-Open` comprises OULAD
plus synthetic sequences; `TRACE-Research` is the optional, license-gated EdNet
extension. Restricted source data and restricted weights must not be distributed.

Synthetic events can exercise mastery, forgetting, effort, hint, confidence,
source-check, calibration, split, and recovery code deterministically:

```powershell
pathlab-adapt generate-synthetic --seed 20260802 --learners 32 `
  --events-per-learner 128 --output D:\TRACE\synthetic.jsonl
```

They validate software only and never support claims about people.

## Evaluation and delivery

Every prediction JSONL row must contain the same ordered `event_id`,
`learner_id`, and boolean `target` plus its model-specific `probability`.
Benchmark kind is derived from source provenance; it is never a CLI assertion.
The dataset manifest binds the adapted event artifact, real source artifacts,
and structured license permissions. The split manifest binds train/validation/
test key artifacts for both learner-disjoint and time-forward protocols, their
seeds, ordered event digests, counts, and recomputed leakage audit.
The diagnostic verifier parses every canonical event (including its source and
boolean target), derives dataset kind/count from those events, reruns both split
algorithms, and compares every partition digest. Caller-written counts, targets,
split rows, source kinds, and leakage status cannot change release behavior.

Benchmark evidence is evaluated in a fixed gate order with a maximum 100,000-row
evaluation cohort. Six prediction files are aligned in one streamed pass:

```powershell
pathlab-adapt benchmark --candidate-predictions student.jsonl `
  --teacher-predictions teacher.jsonl --logistic-predictions logistic.jsonl `
  --bkt-predictions bkt.jsonl --gru-predictions gru.jsonl `
  --transformer-predictions transformer.jsonl `
  --resource-evidence resource.json --model-artifact student.onnx `
  --license-ledger license-ledger.json --dataset-manifest dataset-manifest.json `
  --split-manifest split-manifest.json --evaluation-protocol learner_disjoint `
  --candidate-id student-measured --output evidence.json
pathlab-adapt evaluate-gates --evidence evidence.json --output gates.json
pathlab-adapt produce-manifest --benchmark evidence.json --model-artifact student.onnx `
  --license-ledger license-ledger.json --dataset-manifest dataset-manifest.json `
  --split-manifest split-manifest.json --model-id trace-candidate `
  --output model-manifest.json
```

Every evidence path emits `fixed_order` delivery and `not_approved`, including
valid v2 benchmark evidence. Loose/manual evidence can produce only an
unapproved diagnostic manifest. The v2 verifier still checks actual model and
prediction hashes, finite recomputed metrics, all four baselines, and matching
license/source/dataset/split digests so measured gate results remain useful.
Those results are reported as `all_gates_passed`; they are not release approval
and cannot enable adaptive behavior. Verifier-owned model execution is not
implemented in this release, so there is no library-local attestation,
authority, signature, or adaptive authorization path. Caller objects,
persisted manifests, and duck-typed values are ignored by the controller, which
always pauses into fixed-order delivery.
Candidate size is selected from the measured Pareto frontier; there is no
hard-coded 3M, 8M, or 15M preference. ONNX export remains unapproved in this
fixed-order-only release even when the diagnostic manifest records passed gates,
hashes, quantization, runtime, and reference-device measurements.

Run optional behavioral validation in an environment with the extras installed:

```powershell
python ai-training\scripts\verify_pathlab_adapt_optional.py
```

It exercises actual parameter counts, a 256-event forward pass, four output-head
shapes, distillation loss, int8 export, and ONNX Runtime when available. Missing
optional dependencies are reported as `unverified_missing_dependencies`, never
as a pass or model approval.

Dataset and weight redistribution are separate manifest policies derived from
every validated license entry. An EdNet-enhanced dataset always forces weight
redistribution off even if a caller supplies contrary text or export metadata.
