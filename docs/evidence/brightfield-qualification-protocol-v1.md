# Brightfield synthetic qualification protocol v1

Status: executable pre-qualification harness.

This bounded protocol checks deterministic behavior using generated images only.
It contains no patient material, clinical truth, WSI pixels, or external model
weights. Passing checks cannot qualify or activate a pack. The report remains
`experimental` until independent licensed, tissue-diverse, held-out fixtures and
all pre-registered performance gates pass.

## Cell checks

- Two separated synthetic hematoxylin-like objects must be counted as two.
- Two touching objects must be separated into two instances.
- Exact repeated analysis must be identical.
- Reviewed-region instance-mask output must exist before qualification.

The current connected-component implementation predictably fails touching-object
separation and has no instance-mask output. The harness records these outcomes;
it does not lower the gates or call the implementation watershed.

## IHC checks

- A synthetic image containing 20% DAB-like pixels must report an area fraction
  of 0.2 within `1e-12`.
- Mixed H/DAB-like input without validated controls must remain `relative_only`.
- Weak stain separation must produce `not_evaluable`.
- Marker-specific nuclear/membrane measurement remains `not_evaluable` until
  independent fixtures exist.
- PD-L1 compartment measurement remains `not_evaluable` until the request carries
  reviewed compartment geometry.

Run from the repository root:

```powershell
.\gradlew.bat brightfieldQualification
```

The default report is written atomically to
`build/qualification/brightfield-report.json`. It uses
`pathlab.model-qualification-report/1` and binds each track to the exact pack
manifest checksum.
