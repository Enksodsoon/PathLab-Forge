# Cell-instance qualification protocol v1

Status: frozen restricted-research evaluation protocol.

This protocol evaluates the deterministic optical-density watershed fallback on
one annotation-bearing field from each of 23 MoNuSAC test patients across lung,
kidney, breast, and prostate. The two patients appearing in both the published
training and testing archives are excluded before field selection. Selection is
lexicographically deterministic and does not use annotations or model output.

The immutable gates are:

- macro panoptic quality at least `0.45`;
- instance Dice at least `0.70`;
- mean absolute count error at most `0.15`;
- median matched-instance area/perimeter bias at most `0.10`;
- failed-region rate at most `0.05`;
- exact deterministic repetition;
- evaluable samples from all four declared tissues; and
- execution within the pack's 120-second and 1,024 MiB RAM envelope.

Instances are greedily matched by descending intersection-over-union at a
threshold of `0.50`. Panoptic quality uses the standard matched-IoU numerator
and `TP + 0.5 FP + 0.5 FN` denominator. Instance Dice averages matched Dice
while unmatched instances contribute zero. Count error is absolute count error
divided by the reference count. Morphometry bias is the median of the mean
absolute relative area and perimeter errors for matched instances.

The source lineage is CC BY-NC-SA 4.0 and permits only the restricted private
research track. It is ineligible for Atlas-Clean, commercial use, clinical
claims, diagnosis, or treatment guidance. Locally frozen SHA-256 values bind
the exact official archives; the absence of publisher-supplied cryptographic
checksums must remain visible in the report.
