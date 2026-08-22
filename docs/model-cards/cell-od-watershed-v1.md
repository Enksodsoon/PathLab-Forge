# Optical-density cell fallback

Status: `experimental`. This deterministic CPU fallback currently applies bounded
hematoxylin-like color thresholding and eight-connected component measurements.
The historical pack identifier says `watershed`, but v1 does not yet split
touching nuclei with a validated distance-transform watershed. It must therefore
run only as a `qualification-<hex>` job and must not be activated for staff/demo
evidence until the algorithm name, instance masks, morphometry distributions,
cross-tissue fixtures, and failure-rate gates are resolved.

Reported counts, area, perimeter, eccentricity, and solidity are descriptive
pixel measurements. They are not clinical cell identities, tumor labels, or a
diagnostic result. No learned cell-type prediction is performed.

The executable synthetic protocol is documented in
`docs/evidence/brightfield-qualification-protocol-v1.md`. Its current expected
terminal status is `experimental`, including a failed touching-nuclei check.
