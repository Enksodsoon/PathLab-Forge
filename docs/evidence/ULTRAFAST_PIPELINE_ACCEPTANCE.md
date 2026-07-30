# Ultra-Fast Pipeline Acceptance

Date: 2026-07-30

Reference source:

- VSI/ETS snapshot: 2,001,290,718 bytes
- series: 2
- crop: `51008,5230,103130,75118`
- downsample: `1.5`
- output: `68753x50078`

## Production-safe implementation

The default path retains full-resolution source decoding and the established
quality-preserving whole-image resample for non-integer downsample exports.
The closest-native-pyramid fallback remains behind
`pathlab.forge.experimentalNativeFallback` because it did not pass the image
quality gate.

## Experimental fallback evidence

Cold package-ready benchmark:

- elapsed: 206,918 ms
- process-tree peak: 1,883,074,560 bytes
- peak workspace: 2,151,871,822 bytes
- retained artifact: 1,178,958,736 bytes
- result: all time, RAM, workspace, and retained-disk performance gates passed

Approved-OME comparison:

- minimum sampled SSIM: 0.5599825018080464
- maximum sampled mean Delta E00: 0.5100676466739148
- quality result: failed because SSIM was below 0.97

The fallback is therefore not a production path. This follows the acceptance
rule that performance cannot override image quality.

## Direct-final non-integer resample evidence

A second experiment removed the full-size assembled-image rewrite and resumed
from five verified full-resolution regions after a libvips warning/parser
failure was corrected:

- finalization and package build after checkpoint resume: 176,982 ms
- process-tree peak: 1,042,534,400 bytes
- retained artifact: 1,516,386,811 bytes
- package entries: 17,736
- exact geometry: `68753x50078`

The legacy five-region checkpoint needed a padded recovery join and exceeded
the temporary workspace gate. More importantly, approved-OME comparison
reported minimum sampled SSIM `0.5584282549200127` despite a low maximum mean
Delta E00 of `0.3918308406198163`. Independent per-region resampling changes
spatial phase at region boundaries and is not production-safe. Non-integer
exports therefore continue to use one global resampling step by default.

The experimental direct-final switch remains opt-in. New experiments choose a
nearby region count that divides the final height, eliminating libvips
`arrayjoin` padding, but the path must still pass every image-quality sample
before it can become the default.

## Legacy storage inventory

The existing LocalAppData tree contained 273,521 files totaling
85,045,637,666 bytes (79.205 GiB). No historical payload was deleted. New
system-owned superseded, unapproved payloads are cleaned only after their
replacement reaches `READY`.
