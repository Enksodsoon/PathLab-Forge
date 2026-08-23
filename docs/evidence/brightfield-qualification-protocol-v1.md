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

The current deterministic optical-density, distance-transform watershed passes
the bounded separated-object, touching-object, repeatability, and reviewed-region
RLE mask checks. These are synthetic algorithm checks only. Tissue-diverse,
patient/source-held-out instance accuracy, morphometry bias, and failed-region
gates remain pending, so the pack remains `experimental`.

## IHC checks

- A synthetic image containing 20% DAB-like pixels must report an area fraction
  of 0.2 within `1e-12`.
- Mixed H/DAB-like input without validated controls must remain `relative_only`.
- Weak stain separation must produce `not_evaluable`.
- Marker-aware nuclear and membrane algorithms are present, but their
  qualification result remains `not_evaluable` until independent fixtures exist.
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
