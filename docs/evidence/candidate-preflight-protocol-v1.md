# Candidate preflight protocol v1

This protocol records whether a frozen candidate manifest is executable for a qualification job. It is not a scientific model evaluation and cannot produce a `qualified` result.

A candidate is `not_evaluable` when its pack manifest is absent, malformed, unapproved for qualification execution, missing a rights-cleared artifact, or blocked by a frozen resource or product constraint. The coordinator records the stable failure code `CANDIDATE_PREFLIGHT_FAILED`, completes the track, and signs the resulting capability matrix with the configured local signer.

Transient database and filesystem I/O errors are not converted into scientific terminal states. Activation remains off, no learner evidence is emitted, and no diagnostic or clinical claim is permitted.
